package com.lrj.wms.fulfillment;

import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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
            case "ORDER_CONFLICT", "TRANSFER_CONFLICT", "VERSION_CONFLICT" -> HttpStatus.CONFLICT;
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
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
