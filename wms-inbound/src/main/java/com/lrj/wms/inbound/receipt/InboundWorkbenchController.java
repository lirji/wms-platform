package com.lrj.wms.inbound.receipt;

import com.lrj.wms.inbound.HttpJson;
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

    public InboundWorkbenchController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/inbound-orders")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.page(new InboundReceiptService(session, Clock.systemUTC())
                    .listOrders(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, limit == null ? 50 : limit));
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
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String orderId = firstNonBlank(text(body, "inboundOrderId"), idempotencyKey);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new InboundReceiptService(session, Clock.systemUTC()).createOrder(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, orderId, text(body, "sourceSystem"),
                    text(body, "externalNo"), text(body, "ownerId"), lines(body));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @PostMapping("/inbound-orders/{inboundOrderId}/receipts")
    public ResponseEntity<Map<String, Object>> receive(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String inboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, Clock.systemUTC());
            Map<String, Object> result;
            if (text(body, "deviceId") != null) {
                result = service.receiveObserved(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId,
                        text(body, "lineId"), text(body, "receiptSessionId"), text(body, "receiptPartId"),
                        firstNonBlank(text(body, "clientOperationId"), idempotencyKey), text(body, "deviceId"),
                        text(body, "deviceSessionId"), longValue(body.get("scanSequence"), 1), jwt.getSubject(),
                        qty(body.get("qty")));
            } else {
                result = service.receive(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inboundOrderId,
                        text(body, "lineId"), firstNonBlank(text(body, "clientOperationId"), idempotencyKey),
                        firstNonBlank(text(body, "receiptPartId"), "PART-" + idempotencyKey), jwt.getSubject(),
                        qty(body.get("qty")));
            }
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, inboundOrderId, result, "RECEIVED"));
        }
    }

    @PostMapping("/quality-inspections/{inspectionId}/results")
    public Map<String, Object> inspect(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String inspectionId, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new InboundReceiptService(session, Clock.systemUTC()).inspect(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, inspectionId, text(body, "lineId"),
                    qty(body.get("acceptedQty")), qty(body.get("rejectedQty")), jwt.getSubject(),
                    longValue(body.get("sourceVersion"), 1));
            session.commit();
            return HttpJson.row(result);
        }
    }

    @PostMapping("/tasks/{taskId}/putaways")
    public ResponseEntity<Map<String, Object>> putaway(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new InboundReceiptService(session, Clock.systemUTC()).putaway(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, text(body, "inboundOrderId"), text(body, "lineId"),
                    taskId, firstNonBlank(text(body, "locationId"), text(body, "targetLocationId")),
                    firstNonBlank(text(body, "locationType"), InboundReceiptService.LOCATION_STORAGE),
                    qty(body.get("qty")));
            result.put("clientOperationId", firstNonBlank(text(body, "clientOperationId"), idempotencyKey));
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, text(body, "inboundOrderId"), result, "PUTAWAY"));
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(InboundException.class)
    ResponseEntity<Map<String, Object>> inbound(InboundException error) {
        HttpStatus status = switch (error.code()) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_DOCUMENT", "VERSION_CONFLICT", "OBSERVATION_CONFLICT", "PART_CONFLICT" -> HttpStatus.CONFLICT;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> body) {
        Object raw = body.get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InboundException("INVALID_LINE", "入库行不能为空");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> line = new LinkedHashMap<>((Map<String, Object>) item);
            line.put("lineId", firstNonBlank(text(line, "lineId"), text(line, "externalLineId")));
            lines.add(line);
        }
        return lines;
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
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
