package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 投影只读查询，返回 asOf/lag。写入仍走权威库存校验。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class InventoryViewController {
    private final SqlSessionFactory sessions;

    public InventoryViewController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/inventory")
    public Map<String, Object> inventory(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "warehouseIds", required = false) List<String> warehouseIds,
            @RequestParam(name = "skuId", required = false) String skuId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            throw new IllegalArgumentException("查询必须带仓库");
        }
        String warehouseId = warehouseIds.getFirst();
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return InventoryHttpJson.body(new InventoryProjectionService(session, Clock.systemUTC())
                    .query(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, skuId, limit == null ? 50 : limit));
        }
    }

    @GetMapping("/warehouses/{warehouseId}/inventory/{balanceId}/ledger")
    public Map<String, Object> ledger(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String balanceId, @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return InventoryHttpJson.body(new InventoryAuditService(session).listLedger(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, balanceId, cursor, limit == null ? 50 : limit));
        }
    }

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String, Object>> inventory(InventoryException error) {
        HttpStatus status = error.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(errorBody(error.code(), error.getMessage()));
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("INVALID_SCOPE", error.getMessage()));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", false);
        return body;
    }
}
