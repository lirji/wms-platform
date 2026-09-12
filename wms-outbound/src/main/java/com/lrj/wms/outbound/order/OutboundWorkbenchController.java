package com.lrj.wms.outbound.order;

import com.lrj.wms.outbound.HttpJson;
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

    public OutboundWorkbenchController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/outbound-orders")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.page(new OutboundOrderService(session, Clock.systemUTC())
                    .listOrders(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, limit == null ? 50 : limit));
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
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new OutboundOrderService(session, Clock.systemUTC()).createFromAllocation(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, text(body, "allocationId"),
                    firstNonBlank(text(body, "attemptId"), idempotencyKey), text(body, "ownerId"),
                    text(body, "authorizationId"), lines(body));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/pick-tasks")
    public ResponseEntity<Map<String, Object>> planPick(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String outboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).planPickTask(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, text(body, "orderLineId"),
                    text(body, "sourceLocationId"), text(body, "stagingLocationId"), qty(body.get("qty")));
            result.put("clientOperationId", firstNonBlank(text(body, "clientOperationId"), idempotencyKey));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(result));
        }
    }

    @PostMapping("/tasks/{taskId}/picks")
    public ResponseEntity<Map<String, Object>> pick(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).pickPartial(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, taskId,
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), jwt.getSubject(), qty(body.get("qty")));
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, result, "PICKED"));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/packings")
    public ResponseEntity<Map<String, Object>> pack(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).pack(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, text(body, "orderLineId"),
                    firstNonBlank(text(body, "packageNo"), outboundOrderId + "-PKG"), qty(body.get("qty")));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(result));
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/shipments")
    public ResponseEntity<Map<String, Object>> ship(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String outboundOrderId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).shipPartial(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, text(body, "orderLineId"),
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), jwt.getSubject(), qty(body.get("qty")));
            session.commit();
            Map<String, Object> accepted = accepted(warehouseId, result, "SHIPPED");
            accepted.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/outbound-orders/" + outboundOrderId);
            return ResponseEntity.accepted().body(accepted);
        }
    }

    @PostMapping("/outbound-orders/{outboundOrderId}/cancellations")
    public ResponseEntity<Map<String, Object>> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String outboundOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new OutboundOrderService(session, Clock.systemUTC()).cancelUnpicked(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, outboundOrderId, text(body, "orderLineId"),
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), jwt.getSubject());
            session.commit();
            return ResponseEntity.accepted().body(accepted(warehouseId, result, "CANCEL_REQUESTED"));
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(OutboundException.class)
    ResponseEntity<Map<String, Object>> outbound(OutboundException error) {
        HttpStatus status = switch (error.code()) {
            case "UNKNOWN_ORDER", "UNKNOWN_LINE", "UNKNOWN_TASK" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(HttpJson.error(error.code(), error.getMessage()));
    }

    private static Map<String, Object> accepted(String warehouseId, Map<String, Object> result, String physical) {
        Map<String, Object> body = new LinkedHashMap<>(HttpJson.row(result));
        body.put("physicalStatus", physical);
        body.put("stockSyncStatus", "PENDING");
        body.put("operationId", result.getOrDefault("commandId", result.get("taskId")));
        body.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/outbound-orders/"
                + result.getOrDefault("documentId", result.getOrDefault("orderId", "")));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> body) {
        Object raw = body.get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new OutboundException("INVALID_LINE", "出库行不能为空");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object item : list) {
            lines.add(new LinkedHashMap<>((Map<String, Object>) item));
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
        return java.util.UUID.randomUUID().toString();
    }

    private static BigDecimal qty(Object value) {
        if (value == null) {
            throw new OutboundException("INVALID_QTY", "数量不能为空");
        }
        return new BigDecimal(String.valueOf(value));
    }
}
