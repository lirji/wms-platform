package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.security.ScopeForbiddenException;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主数据写 HTTP。新仓不会自动进入 JWT，调用方须更新身份后才能选仓。 */
@RestController
@RequestMapping("/api/wms/v1")
@ConditionalOnBean(SqlSessionFactory.class)
public class MasterdataCommandController {
    private final SqlSessionFactory sessions;

    public MasterdataCommandController(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/warehouses")
    public ResponseEntity<Map<String, Object>> createWarehouse(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "masterdata.write");
        String key = MasterdataHttp.requireMatchingKey(idempotencyKey, MasterdataHttp.text(body, "clientOperationId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new MasterdataCommandService(session, Clock.systemUTC()).createWarehouse(
                    WmsJwtAuthorities.enterpriseId(jwt), MasterdataHttp.requireText(body, "code", "仓编码"),
                    MasterdataHttp.requireText(body, "name", "仓名称"),
                    MasterdataHttp.requireText(body, "timezone", "时区"), key);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(MasterdataHttp.row(created));
        }
    }

    @PostMapping("/warehouses/{warehouseId}/locations")
    public ResponseEntity<Map<String, Object>> createLocation(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "masterdata.write");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = MasterdataHttp.requireMatchingKey(idempotencyKey, MasterdataHttp.text(body, "clientOperationId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new MasterdataCommandService(session, Clock.systemUTC()).createLocation(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    MasterdataHttp.requireText(body, "code", "库位编码"),
                    MasterdataHttp.requireText(body, "zoneCode", "库区编码"),
                    MasterdataHttp.requireText(body, "locationType", "库位类型"),
                    MasterdataHttp.decimal(body.get("capacityQty")), MasterdataHttp.text(body, "capacityUnit"), key);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(MasterdataHttp.row(created));
        }
    }

    @PostMapping("/skus")
    public ResponseEntity<Map<String, Object>> createSku(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "masterdata.write");
        String key = MasterdataHttp.requireMatchingKey(idempotencyKey, MasterdataHttp.text(body, "clientOperationId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new MasterdataCommandService(session, Clock.systemUTC()).createSku(
                    WmsJwtAuthorities.enterpriseId(jwt), MasterdataHttp.requireText(body, "code", "商品编码"),
                    MasterdataHttp.requireText(body, "name", "商品名称"),
                    MasterdataHttp.requireText(body, "baseUnit", "基础单位"), MasterdataHttp.integer(body, "quantityScale"),
                    MasterdataHttp.bool(body, "lotEnabled"), MasterdataHttp.bool(body, "serialEnabled"),
                    MasterdataHttp.bool(body, "expiryEnabled"), key);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(MasterdataHttp.row(created));
        }
    }

    @PostMapping("/skus/{skuId}/units")
    public ResponseEntity<Map<String, Object>> addSkuUnit(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "masterdata.write");
        String key = MasterdataHttp.requireMatchingKey(idempotencyKey, MasterdataHttp.text(body, "clientOperationId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new MasterdataCommandService(session, Clock.systemUTC()).addSkuUnit(
                    WmsJwtAuthorities.enterpriseId(jwt), skuId, MasterdataHttp.requireText(body, "unitCode", "单位编码"),
                    MasterdataHttp.decimal(MasterdataHttp.requireText(body, "numerator", "换算分子")),
                    MasterdataHttp.decimal(MasterdataHttp.requireText(body, "denominator", "换算分母")),
                    MasterdataHttp.decimal(body.get("sampleQuantity")), key);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(MasterdataHttp.row(created));
        }
    }

    @PostMapping("/warehouses/{warehouseId}/lots")
    public ResponseEntity<Map<String, Object>> createLot(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        WmsJwtAuthorities.requireScope(jwt, "masterdata.write");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String key = MasterdataHttp.requireMatchingKey(idempotencyKey, MasterdataHttp.text(body, "clientOperationId"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> created = new MasterdataCommandService(session, Clock.systemUTC()).createLot(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    MasterdataHttp.requireText(body, "ownerId", "货权主体"),
                    MasterdataHttp.requireText(body, "skuId", "商品标识"),
                    MasterdataHttp.requireText(body, "lotCode", "批次编码"),
                    MasterdataHttp.requireText(body, "businessLotKey", "跨仓批次键"),
                    instant(MasterdataHttp.text(body, "producedAt")), instant(MasterdataHttp.text(body, "expiresAt")),
                    MasterdataHttp.text(body, "sourceDate"), MasterdataHttp.longValue(body, "expiryRuleVersion", 0L),
                    key);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(MasterdataHttp.row(created));
        }
    }

    @ExceptionHandler(ScopeForbiddenException.class)
    ResponseEntity<Map<String, Object>> scope(ScopeForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MasterdataHttp.error("SCOPE_FORBIDDEN", "缺少写主数据权限"));
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(MasterdataHttp.error("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(MasterdataException.class)
    ResponseEntity<Map<String, Object>> masterdata(MasterdataException error) {
        return MasterdataHttp.statusOf(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        String message = error.getMessage() == null ? "请求无效" : error.getMessage();
        if (message.contains("企业")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(MasterdataHttp.error("ENTERPRISE_SCOPE_MISSING", "令牌缺少企业范围"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MasterdataHttp.error("INVALID_ARGUMENT", message));
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
