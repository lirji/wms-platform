package com.lrj.wms.fulfillment;

import com.lrj.wms.security.ScopeForbiddenException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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

/** 履约与调拨查询/建单。跨仓进度按仓展示，不因单仓 Confirmed 显示整单成功。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class FulfillmentWorkbenchController {
    private final SqlSessionFactory sessions;

    public FulfillmentWorkbenchController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/fulfillments")
    public Map<String, Object> listFulfillments(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", required = false) Integer limit) {
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.page(new FulfillmentService(session, Clock.systemUTC())
                    .list(WmsJwtAuthorities.enterpriseId(jwt), limit == null ? 50 : limit));
        }
    }

    @GetMapping("/fulfillments/{fulfillmentId}")
    public Map<String, Object> getFulfillment(@AuthenticationPrincipal Jwt jwt, @PathVariable String fulfillmentId) {
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.row(new FulfillmentService(session, Clock.systemUTC())
                    .get(WmsJwtAuthorities.enterpriseId(jwt), fulfillmentId));
        }
    }

    @PostMapping("/fulfillments")
    public ResponseEntity<Map<String, Object>> createFulfillment(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new FulfillmentService(session, Clock.systemUTC()).createOrder(
                    WmsJwtAuthorities.enterpriseId(jwt), text(body, "sourceSystem"), text(body, "sourceOrderNo"),
                    digest(body, idempotencyKey), lines(body, "requestedQty", "baseUnit"),
                    longValue(body.get("strategyVersion"), 1));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @PostMapping("/fulfillments/{fulfillmentId}/cancellations")
    public ResponseEntity<Map<String, Object>> cancelFulfillment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String fulfillmentId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "fulfillment.cancel");
        String key = firstNonBlank(text(body, "clientOperationId"), idempotencyKey);
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> result = new FulfillmentService(session, Clock.systemUTC()).requestCancel(
                    WmsJwtAuthorities.enterpriseId(jwt), fulfillmentId, key, text(body, "reason"),
                    body.get("expectedVersion") == null ? null : longValue(body.get("expectedVersion"), 0),
                    jwt.getSubject());
            session.commit();
            Map<String, Object> accepted = accepted(result, "CANCEL_REQUESTED");
            accepted.put("statusUrl", "/api/wms/v1/fulfillments/" + fulfillmentId);
            accepted.put("stockSyncStatus", "NOT_APPLICABLE");
            return ResponseEntity.accepted().body(accepted);
        }
    }

    @PostMapping("/fulfillments/{fulfillmentId}/attempts")
    public ResponseEntity<Map<String, Object>> prepareAttempt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String fulfillmentId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        try (SqlSession session = sessions.openSession(false)) {
            List<Map<String, Object>> participants = participantLines(body);
            Map<String, Object> created = new FulfillmentService(session, Clock.systemUTC()).createAttempt(
                    WmsJwtAuthorities.enterpriseId(jwt), fulfillmentId, deadline(body.get("deadline")),
                    warehousesOf(body, participants), participants);
            created.put("clientOperationId", firstNonBlank(text(body, "clientOperationId"), idempotencyKey));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @GetMapping("/transfers")
    public Map<String, Object> listTransfers(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", required = false) Integer limit) {
        try (SqlSession session = sessions.openSession()) {
            return HttpJson.page(new TransferService(session, Clock.systemUTC())
                    .list(WmsJwtAuthorities.enterpriseId(jwt), limit == null ? 50 : limit));
        }
    }

    @GetMapping("/transfers/{transferId}")
    public Map<String, Object> getTransfer(@AuthenticationPrincipal Jwt jwt, @PathVariable String transferId,
            @RequestParam(name = "warehouseId", required = false) String warehouseId) {
        if (warehouseId != null) {
            WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        }
        try (SqlSession session = sessions.openSession()) {
            Map<String, Object> transfer = new TransferService(session, Clock.systemUTC())
                    .get(WmsJwtAuthorities.enterpriseId(jwt), transferId);
            if (warehouseId != null && !warehouseId.equals(String.valueOf(transfer.get("sourceWarehouseId")))
                    && !warehouseId.equals(String.valueOf(transfer.get("targetWarehouseId")))) {
                throw new WarehouseForbiddenException(warehouseId);
            }
            return HttpJson.row(transfer);
        }
    }

    @PostMapping("/transfers/{transferId}/issues")
    public ResponseEntity<Map<String, Object>> issueTransfer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String transferId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        try (SqlSession session = sessions.openSession(false)) {
            TransferService service = new TransferService(session, Clock.systemUTC());
            Map<String, Object> transfer = service.get(WmsJwtAuthorities.enterpriseId(jwt), transferId);
            WmsJwtAuthorities.requireWarehouse(jwt, String.valueOf(transfer.get("sourceWarehouseId")));
            Map<String, Object> result = service.issue(WmsJwtAuthorities.enterpriseId(jwt), transferId,
                    firstNonBlank(text(body, "lineId"), text(body, "transferLineId")),
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), qty(body.get("qty")));
            session.commit();
            return ResponseEntity.accepted().body(accepted(result, "ISSUED"));
        }
    }

    @PostMapping("/transfers/{transferId}/receipt-authorizations")
    public ResponseEntity<Map<String, Object>> authorizeTransferReceipt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String transferId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        try (SqlSession session = sessions.openSession(false)) {
            TransferService service = new TransferService(session, Clock.systemUTC());
            Map<String, Object> transfer = service.get(WmsJwtAuthorities.enterpriseId(jwt), transferId);
            WmsJwtAuthorities.requireWarehouse(jwt, String.valueOf(transfer.get("targetWarehouseId")));
            Map<String, Object> result = service.authorizeReceipt(WmsJwtAuthorities.enterpriseId(jwt), transferId,
                    firstNonBlank(text(body, "lineId"), text(body, "transferLineId")),
                    firstNonBlank(text(body, "targetClientOperationId"), text(body, "clientOperationId"), idempotencyKey),
                    qty(firstNonNull(body.get("quantity"), body.get("qty"))));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(result));
        }
    }

    @PostMapping("/warehouses/{warehouseId}/transfer-receipts")
    public ResponseEntity<Map<String, Object>> receiveTransfer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession(false)) {
            TransferService service = new TransferService(session, Clock.systemUTC());
            Map<String, Object> transfer = service.get(WmsJwtAuthorities.enterpriseId(jwt), text(body, "transferId"));
            if (!warehouseId.equals(String.valueOf(transfer.get("targetWarehouseId")))) {
                throw new WarehouseForbiddenException(warehouseId);
            }
            Map<String, Object> result = service.receive(WmsJwtAuthorities.enterpriseId(jwt), text(body, "transferId"),
                    firstNonBlank(text(body, "lineId"), text(body, "sourceLineRef")),
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), text(body, "authorizationId"),
                    longValue(body.get("tokenVersion"), 0), qty(body.get("qty")), text(body, "targetLotId"));
            session.commit();
            return ResponseEntity.accepted().body(accepted(result, "RECEIVED"));
        }
    }

    @PostMapping("/transfers/{transferId}/losses")
    public ResponseEntity<Map<String, Object>> confirmTransferLoss(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String transferId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        try (SqlSession session = sessions.openSession(false)) {
            TransferService service = new TransferService(session, Clock.systemUTC());
            Map<String, Object> transfer = service.get(WmsJwtAuthorities.enterpriseId(jwt), transferId);
            WmsJwtAuthorities.requireWarehouse(jwt, String.valueOf(transfer.get("sourceWarehouseId")));
            Map<String, Object> result = service.confirmLoss(WmsJwtAuthorities.enterpriseId(jwt), transferId,
                    firstNonBlank(text(body, "lineId"), text(body, "transferLineId")),
                    firstNonBlank(text(body, "clientOperationId"), idempotencyKey), qty(body.get("qty")));
            session.commit();
            return ResponseEntity.accepted().body(accepted(result, "LOSS_CONFIRMED"));
        }
    }

    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Object>> createTransfer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireWarehouse(jwt, text(body, "sourceWarehouseId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new TransferService(session, Clock.systemUTC()).create(
                    WmsJwtAuthorities.enterpriseId(jwt), firstNonBlank(text(body, "transferId"), idempotencyKey),
                    text(body, "sourceWarehouseId"), text(body, "targetWarehouseId"),
                    lines(body, "plannedQty", "unit"));
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(HttpJson.row(created));
        }
    }

    @ExceptionHandler(ScopeForbiddenException.class)
    ResponseEntity<Map<String, Object>> scope(ScopeForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("SCOPE_FORBIDDEN", "缺少作业权限"));
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(HttpJson.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler({FulfillmentException.class, TransferException.class})
    ResponseEntity<Map<String, Object>> domain(RuntimeException error) {
        String code = error instanceof FulfillmentException fulfillment ? fulfillment.code()
                : ((TransferException) error).code();
        HttpStatus status = switch (code) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ORDER_CONFLICT", "TRANSFER_CONFLICT", "VERSION_CONFLICT", "IDEMPOTENCY_PAYLOAD_MISMATCH" ->
                HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(HttpJson.error(code, error.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> body, String qtyKey, String unitKey) {
        Object raw = body.get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new FulfillmentException("INVALID_LINE", "行不能为空");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> line = new LinkedHashMap<>((Map<String, Object>) item);
            if (line.get(qtyKey) == null && line.get("qty") != null) {
                line.put(qtyKey, line.get("qty"));
            }
            if (line.get(unitKey) == null && line.get("unit") != null) {
                line.put(unitKey, line.get("unit"));
            }
            if (line.get("lineId") == null && line.get("sourceLineId") != null) {
                line.put("lineId", line.get("sourceLineId"));
            }
            lines.add(line);
        }
        return lines;
    }

    private static String digest(Map<String, Object> body, String fallback) {
        String provided = text(body, "digest");
        if (provided != null) {
            return provided;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((fallback + '|' + body.get("sourceOrderNo") + '|' + body.get("lines"))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
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

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value == null ? fallback : value));
    }

    private static Instant deadline(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now().plusSeconds(3600);
        }
        return Instant.parse(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private static List<Map<String, Object>> participantLines(Map<String, Object> body) {
        List<Map<String, Object>> lines = lines(body, "qty", "baseUnit");
        for (Map<String, Object> line : lines) {
            if (line.get("orderLineId") == null && line.get("sourceLineId") != null) {
                line.put("orderLineId", line.get("sourceLineId"));
            }
        }
        return lines;
    }

    private static List<String> warehousesOf(Map<String, Object> body, List<Map<String, Object>> lines) {
        List<String> warehouses = new ArrayList<>(stringList(body.get("warehouses")));
        for (Map<String, Object> line : lines) {
            Object warehouseId = line.get("warehouseId");
            if (warehouseId != null && !warehouses.contains(String.valueOf(warehouseId))) {
                warehouses.add(String.valueOf(warehouseId));
            }
        }
        return warehouses;
    }

    private static Map<String, Object> accepted(Map<String, Object> result, String physical) {
        Map<String, Object> body = new LinkedHashMap<>(HttpJson.row(result));
        body.put("physicalStatus", physical);
        body.put("stockSyncStatus", "PENDING");
        body.put("operationId", result.getOrDefault("operationId", result.get("authorizationId")));
        return body;
    }

    private static BigDecimal qty(Object value) {
        if (value == null) {
            throw new TransferException("INVALID_QTY", "数量不能为空");
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
