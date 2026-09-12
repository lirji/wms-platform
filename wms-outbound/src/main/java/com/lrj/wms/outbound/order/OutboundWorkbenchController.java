package com.lrj.wms.outbound.order;

import com.lrj.wms.outbound.HttpJson;
import com.lrj.wms.security.ScopeForbiddenException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** 出库工作台 HTTP。拣货/发运返回 202，货已执行库存待同步时禁止伪装完成。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class OutboundWorkbenchController {
    private final SqlSessionFactory sessions;

    private final boolean messagingEnabled;
    public OutboundWorkbenchController(SqlSessionFactory sessions,
            @org.springframework.beans.factory.annotation.Value("${wms.messaging.enabled:false}") boolean messagingEnabled) {
        this.sessions = sessions; this.messagingEnabled = messagingEnabled;
    }

    @GetMapping("/outbound-orders")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        var page = com.lrj.wms.runtime.web.CursorPage.chronological(limit, cursor,
                com.lrj.wms.runtime.web.CursorPage.scope("outbound", WmsJwtAuthorities.enterpriseId(jwt), warehouseId));
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.cursorPage(page.result(session.getMapper(OutboundOrderMapper.class)
                    .listOrdersPage(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, page), true));
        }
    }

    @GetMapping("/outbound-orders/{outboundOrderId}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.row(new OutboundOrderService(session, Clock.systemUTC())
                    .getOrder(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId));
        }
    }

    @PostMapping("/outbound-orders")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.CreateRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new OutboundOrderService(session, Clock.systemUTC()).createFromAllocation(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, body.allocationId(),
                    firstNonBlank(body.attemptId(), idempotencyKey), body.ownerId(),
                    body.authorizationId(), lines(body.lines()));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/execution-authorizations")
    public Map<String, Object> authorize(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.AuthorizeRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "fulfillment.execute");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundAuthorizationService(session, Clock.systemUTC()).authorize(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, key, jwt.getSubject(),
                    body.attemptId(), body.authorizationId(), body.xid(),
                    body.tcTerminalEvidenceRef(), body.participantSetHash());
            session.commit();
            return HttpJson.row(result);
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/pick-tasks")
    public ResponseEntity<Map<String, Object>> planPick(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String outboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.PlanPickRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).planPickTask(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, body.orderLineId(),
                    body.sourceLocationId(), body.stagingLocationId(), qty(body.qty()),
                    com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()));
            result.put("clientOperationId", com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(result));
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
            return HttpJson.cursorPage(new OutboundTaskService(session, Clock.systemUTC())
                    .list(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskType, cursor, limit == null ? 50 : limit));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getTask(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId) {
        WmsJwtAuthorities.requireScope(jwt, "task.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.row(new OutboundTaskService(session, Clock.systemUTC())
                    .get(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId));
        }
    }

    @PostMapping("/tasks/{taskId}/claims")
    public Map<String, Object> claim(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.ClaimRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "task.claim");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        long expectedVersion = body.expectedVersion();
        if (expectedVersion < 0) {
            throw new OutboundException("INVALID_VERSION", "expectedVersion不能为空");
        }
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundTaskService(session, Clock.systemUTC()).claim(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId, jwt.getSubject(), expectedVersion);
            result.put("clientOperationId", com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()));
            session.commit();
            return HttpJson.row(result);
        }
    }

    @PostMapping("/tasks/{taskId}/picks")
    public ResponseEntity<Map<String, Object>> pick(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.PickRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            requireContext(body.lotId(), body.lotId());
            if(body.serialExecution()!=null && body.lotId()==null) throw new OutboundException("SERIAL_PICK_CONTEXT_REQUIRED","序列拣货必须提供明确批次和过账上下文");
            Map<String, Object> result = body.lotId() == null
                    ? new OutboundOrderService(session, Clock.systemUTC()).pickPartial(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                        taskId, com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), qty(body.qty()), body.pickPartId())
                    : new OutboundPostingService(session, Clock.systemUTC()).pick(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId,
                    com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), qty(body.qty()), body.pickPartId(), body.lotId(), body.serialExecution());
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, result, "PICKED"));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/packings")
    public ResponseEntity<Map<String, Object>> pack(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId, @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.PackRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).pack(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, body.orderLineId(),
                    firstNonBlank(body.packageNo(), outboundOrderId + "-PKG"), qty(body.qty()));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(result));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/shipments")
    public ResponseEntity<Map<String, Object>> ship(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.ShipRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            requireContext(body.stagingLocationId(), body.lotId());
            Map<String, Object> result = body.lotId() == null
                    ? new OutboundOrderService(session, Clock.systemUTC()).shipPartial(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                        outboundOrderId, body.orderLineId(), com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), qty(body.qty()), body.shipmentPartId())
                    : new OutboundPostingService(session, Clock.systemUTC()).ship(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, body.orderLineId(),
                    com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), qty(body.qty()), body.shipmentPartId(), body.stagingLocationId(), body.lotId());
            session.commit();
            Map<String, Object> accepted = accepted(warehouseId, result, "SHIPPED");
            accepted.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/outbound-orders/" + outboundOrderId);
            return ResponseEntity.accepted().body(accepted);
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/cancellations")
    public ResponseEntity<Map<String, Object>> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String outboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @jakarta.validation.Valid @RequestBody OutboundWorkbenchRequests.CancelRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            requireContext(body.sourceLocationId(), body.lotId());
            Map<String, Object> result = body.lotId() == null
                    ? new OutboundOrderService(session, Clock.systemUTC()).cancelUnpicked(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                        outboundOrderId, body.orderLineId(), com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), body.qty())
                    : new OutboundPostingService(session, Clock.systemUTC()).cancel(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, body.orderLineId(),
                    com.lrj.wms.runtime.command.CommandKeys.resolve(idempotencyKey, body.clientOperationId()), jwt.getSubject(), body.sourceLocationId(), body.lotId(), body.qty());
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, result, "CANCEL_REQUESTED"));
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

    @ExceptionHandler(OutboundException.class)
    ResponseEntity<Map<String, Object>> outbound(OutboundException error) {
        HttpStatus status = switch (error.code()) {
            case "UNKNOWN_ORDER", "UNKNOWN_LINE", "UNKNOWN_TASK" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "TASK_NOT_CLAIMABLE", "TCC_NOT_COMMITTED", "EVIDENCE_MISMATCH", "AUTH_CONFLICT",
                    "AUTH_REQUIRED", "IDEMPOTENCY_PAYLOAD_MISMATCH", "SERIAL_PICK_CONFLICT", "SERIAL_PICK_CONTEXT_REQUIRED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(HttpJson.error(error.code(), error.getMessage()));
    }

    /** 旧无消息客户端保留兼容入口；启用真实投递后，缺原始桶只能拒绝，不能猜测。 */
    private void requireContext(String location, String lot) {
        if ((location == null) != (lot == null) || messagingEnabled && (location == null || lot == null)
                || location != null && location.isBlank() || lot != null && lot.isBlank())
            throw new OutboundException("POSTING_CONTEXT_REQUIRED", "启用消息后需要原始库位和批次，且必须成组提供");
    }

    private static Map<String, Object> accepted(String warehouseId, Map<String, Object> result, String physical) {
        Map<String, Object> body = new LinkedHashMap<>(HttpJson.row(result));
        body.put("physicalStatus", physical);
        body.put("stockSyncStatus", "APPLIED".equals(result.get("state")) ? "POSTED" : result.getOrDefault("state", "PENDING"));
        body.put("operationId", result.getOrDefault("commandId", result.get("taskId")));
        body.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/outbound-orders/"
                + result.getOrDefault("documentId", result.getOrDefault("orderId", "")));
        return body;
    }

    /** 协议行映射到现有应用模型，精确数量不经过浮点数转换。 */
    private static List<Map<String, Object>> lines(List<OutboundWorkbenchRequests.OutboundLine> values) {
        return values.stream().map(value -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderLineId", value.orderLineId()); line.put("skuId", value.skuId());
            line.put("qty", value.qty()); line.put("baseUnit", value.baseUnit() == null ? "EA" : value.baseUnit());
            return line;
        }).toList();
    }



    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return java.util.UUID.randomUUID().toString();
    }

    private static BigDecimal qty(Object value) {
        if (value == null) {
            throw new OutboundException("INVALID_QTY", "数量不能为空");
        }
        return new BigDecimal(String.valueOf(value));
    }
}
