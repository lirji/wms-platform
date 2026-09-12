package com.lrj.wms.inbound.receipt;

import com.lrj.wms.inbound.HttpJson;
import com.lrj.wms.security.ScopeForbiddenException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 入库工作台 HTTP。收货/上架返回 202，不把受理当库存已过账。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class InboundWorkbenchController {
    private final SqlSessionFactory sessions;

    private final boolean messagingEnabled;

    public InboundWorkbenchController(SqlSessionFactory sessions,
            @org.springframework.beans.factory.annotation.Value("${wms.messaging.enabled:false}") boolean messagingEnabled) {
        this.sessions = sessions;
        this.messagingEnabled = messagingEnabled;
    }

    @GetMapping("/inbound-orders")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        var page = com.lrj.wms.runtime.web.CursorPage.chronological(limit, cursor,
                com.lrj.wms.runtime.web.CursorPage.scope("inbound", WmsJwtAuthorities.enterpriseId(jwt), warehouseId));
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.cursorPage(page.result(session.getMapper(InboundReceiptMapper.class)
                    .listOrdersPage(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, page), true));
        }
    }

    @GetMapping("/inbound-orders/{inboundOrderId}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String inboundOrderId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.row(new InboundReceiptService(session, Clock.systemUTC())
                    .getOrder(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId));
        }
    }

    @PostMapping("/inbound-orders")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody InboundWorkbenchRequests.CreateRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String orderId = firstNonBlank(body.inboundOrderId(), idempotencyKey);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new InboundReceiptService(session, Clock.systemUTC()).createOrder(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, orderId, body.sourceSystem(),
                    body.externalNo(), body.ownerId(), lines(body.lines()));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    /** 收货批次按本单据授权范围分页，供质检与上架选择同一个事实。 */
    @GetMapping("/inbound-orders/{inboundOrderId}/receipts")
    public Map<String, Object> receiptBatches(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String inboundOrderId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        WmsJwtAuthorities.requireScope(jwt, "inbound.read"); WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (var session = sessions.openSession()) {
            return HttpJson.cursorPage(new ReceiptQualityService(session, Clock.systemUTC())
                    .batches(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId, limit, cursor));
        }
    }

    @PostMapping("/inbound-orders/{inboundOrderId}/receipts")
    public ResponseEntity<Map<String, Object>> receive(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String inboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @jakarta.validation.Valid @RequestBody InboundWorkbenchRequests.ReceiveRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        if (messagingEnabled && (body.locationId() == null || body.lotId() == null)) {
            throw new InboundException("MISSING_POSTING_CONTEXT", "消息收货必须提供库位和批次标识");
        }
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, Clock.systemUTC());
            Map<String, Object> result;
            if (body.deviceId() != null) {
                result = service.receiveObserved(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId,
                        body.lineId(), body.receiptSessionId(), body.receiptPartId(),
                        com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), body.deviceId(),
                        body.deviceSessionId(), longValue(body.scanSequence(), 1), jwt.getSubject(),
                        qty(body.qty()));
            } else {
                result = service.receive(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId,
                        body.lineId(), com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()),
                        firstNonBlank(body.receiptPartId(), "PART-" + idempotencyKey), jwt.getSubject(),
                        qty(body.qty()));
            }
            if (body.locationId() != null) service.bindReceiveContext(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    inboundOrderId, body.lineId(), result, body.locationId(), body.lotId());
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, inboundOrderId, result, "RECEIVED"));
        }
    }

    @PostMapping("/quality-inspections/{inspectionId}/results")
    public ResponseEntity<Map<String, Object>> inspect(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String inspectionId, @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @jakarta.validation.Valid @RequestBody InboundWorkbenchRequests.InspectRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        if (messagingEnabled && (body.receiptCommandId() == null || body.receiptCommandId().isBlank())) {
            throw new InboundException("RECEIPT_BATCH_REQUIRED", "消息质检必须明确原收货批次命令");
        }
        try (SqlSession session = sessions.openSession(false)) {
            if (body.receiptCommandId() != null) {
                com.lrj.wms.contract.messaging.ReceiptQualityDecision decision;
                try { decision = new com.lrj.wms.contract.messaging.ReceiptQualityDecision(body.receiptCommandId(), inspectionId,
                        longValue(body.sourceVersion(), 1), body.acceptedQty(), body.rejectedQty()); }
                catch (IllegalArgumentException invalid) { throw new InboundException("INVALID_QUALITY_DECISION", "分批质检数量或版本无效"); }
                var result = new ReceiptQualityService(session, Clock.systemUTC()).inspect(WmsJwtAuthorities.enterpriseId(jwt),
                        warehouseId, body.lineId(), commandId, jwt.getSubject(), decision);
                session.commit();
                result.put("operationId", result.get("commandId"));
                result.put("stockSyncStatus", "APPLIED".equals(result.get("state")) ? "POSTED" : "PENDING");
                return ResponseEntity.accepted().body(HttpJson.row(result));
            }
            Map<String, Object> result = new InboundReceiptService(session, Clock.systemUTC()).inspect(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inspectionId, body.lineId(),
                    qty(body.acceptedQty()), qty(body.rejectedQty()), jwt.getSubject(),
                    longValue(body.sourceVersion(), 1));
            session.commit();
            return ResponseEntity.ok(HttpJson.row(result));
        }
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "taskType") String taskType,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireScope(jwt, "task.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.cursorPage(new InboundTaskService(session, Clock.systemUTC())
                    .list(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskType, cursor, limit == null ? 50 : limit));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getTask(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId) {
        WmsJwtAuthorities.requireScope(jwt, "task.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.row(new InboundTaskService(session, Clock.systemUTC())
                    .get(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId));
        }
    }

    @PostMapping("/tasks/{taskId}/claims")
    public Map<String, Object> claim(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody InboundWorkbenchRequests.ClaimRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "task.claim");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        long expectedVersion = longValue(body.expectedVersion(), -1);
        if (expectedVersion < 0) {
            throw new InboundException("INVALID_VERSION", "expectedVersion不能为空");
        }
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new InboundTaskService(session, Clock.systemUTC()).claim(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId, jwt.getSubject(), expectedVersion);
            result.put("clientOperationId", com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()));
            session.commit();
            return HttpJson.row(result);
        }
    }

    @PostMapping("/tasks/{taskId}/putaways")
    public ResponseEntity<Map<String, Object>> putaway(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @jakarta.validation.Valid @RequestBody InboundWorkbenchRequests.PutawayRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        if (messagingEnabled && (body.receiptCommandId() == null || body.receiptCommandId().isBlank()))
            throw new InboundException("RECEIPT_BATCH_REQUIRED", "消息上架必须指定原收货批次");
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new InboundReceiptService(session, Clock.systemUTC()).putaway(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, body.inboundOrderId(), body.lineId(),
                    taskId, firstNonBlank(body.locationId(), body.targetLocationId()),
                    firstNonBlank(body.locationType(), InboundReceiptService.LOCATION_STORAGE),
                    qty(body.qty()), com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), body.receiptCommandId());
            result.put("clientOperationId", com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()));
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, body.inboundOrderId(), result, "PUTAWAY"));
        }
    }

    @ExceptionHandler(ScopeForbiddenException.class)
    ResponseEntity<Map<String, Object>> scope(ScopeForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("SCOPE_FORBIDDEN", "缺少任务权限"));
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(InboundException.class)
    ResponseEntity<Map<String, Object>> inbound(InboundException error) {
        HttpStatus status = switch (error.code()) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_DOCUMENT", "DUPLICATE_INSPECTION", "VERSION_CONFLICT", "OBSERVATION_CONFLICT", "PART_CONFLICT",
                    "TASK_NOT_CLAIMABLE" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(HttpJson.error(error.code(), error.getMessage()));
    }

    private static Map<String, Object> accepted(String warehouseId, String orderId, Map<String, Object> result,
            String physical) {
        Map<String, Object> body = new LinkedHashMap<>(HttpJson.row(result));
        body.put("physicalStatus", physical);
        body.put("stockSyncStatus", "PENDING");
        body.put("operationId", result.getOrDefault("commandId", result.get("effectId")));
        body.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/inbound-orders/" + orderId);
        return body;
    }

    /** 旧应用用例仍接收行模型；只在协议边界进行显式映射。 */
    private static List<Map<String, Object>> lines(List<InboundWorkbenchRequests.InboundLine> values) {
        return values.stream().map(value -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", firstNonBlank(value.lineId(), value.externalLineId()));
            line.put("externalLineId", value.externalLineId());
            line.put("skuId", value.skuId());
            line.put("expectedQty", value.expectedQty());
            line.put("unit", value.unit() == null ? "EA" : value.unit());
            return line;
        }).toList();
    }



    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static BigDecimal qty(Object value) {
        if (value == null) {
            throw new InboundException("INVALID_QTY", "数量不能为空");
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
