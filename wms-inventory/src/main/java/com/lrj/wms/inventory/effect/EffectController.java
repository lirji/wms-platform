package com.lrj.wms.inventory.effect;

import com.lrj.wms.inventory.effect.domain.EffectCodes;
import com.lrj.wms.inventory.effect.domain.EffectProtocolException;
import com.lrj.wms.inventory.query.InventoryAuditService;
import com.lrj.wms.security.WarehouseForbiddenException;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.time.Clock;
import java.util.LinkedHashMap;
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

/** 效果身份 HTTP；数据来自库存库，页面不得写死或刷新随机键重做。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}")
@ConditionalOnBean(SqlSessionFactory.class)
public class EffectController {
    private final SqlSessionFactory sessions;
    private final Clock clock;

    public EffectController(SqlSessionFactory sessions) {
        this.sessions = sessions;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/action-effects")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return new InventoryAuditService(session).listEffects(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, null,
                    cursor, limit == null ? 50 : limit);
        }
    }

    @GetMapping("/tasks/{taskId}/action-effects")
    public Map<String, Object> listByTask(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String taskId, @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return new InventoryAuditService(session).listEffects(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                    taskId, cursor, limit == null ? 50 : limit);
        }
    }

    /** 按权威事实登记或恢复 effect。 */
    @PostMapping("/action-effects")
    public ResponseEntity<Map<String, Object>> register(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody EffectRequests.RegisterRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String clientOperationId = requireMatchingKey(idempotencyKey, body.clientOperationId());
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> resource = new EffectService(session, clock).register(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, body.action(), body.factType(),
                    body.factParentId(), body.factPartId(), body.factLineId(), clientOperationId);
            session.commit();
            return ResponseEntity.status(HttpStatus.CREATED).body(resource);
        }
    }

    /** 查询 active/applied 与 safeToRetry。 */
    @GetMapping("/action-effects/{effectId}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String effectId) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        try (SqlSession session = sessions.openSession()) {
            return new EffectService(session, clock).get(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, effectId);
        }
    }

    /** 安全重授权；未知状态返回 202 且不发新尝试。 */
    @PostMapping("/action-effects/{effectId}/execution-attempts")
    public ResponseEntity<Map<String, Object>> createAttempt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String warehouseId, @PathVariable String effectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @jakarta.validation.Valid @RequestBody EffectRequests.CreateAttemptRequest body) {
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        String clientOperationId = requireMatchingKey(idempotencyKey, body.clientOperationId());
        long expected = body.expectedEffectVersion() == null ? -1L
                : ((Number) body.expectedEffectVersion()).longValue();
        if (expected < 0) {
            throw new IllegalArgumentException("expectedEffectVersion不能为空");
        }
        Long digestVersion = body.digestVersion() == null ? null : ((Number) body.digestVersion()).longValue();
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> accepted = new EffectService(session, clock).createAttempt(
                    WmsJwtAuthorities.enterpriseId(jwt), warehouseId, effectId, body.previousCommandId(), expected,
                    digestVersion, clientOperationId);
            session.commit();
            accepted.put("operationId", accepted.getOrDefault("executionAttemptId", accepted.get("id")));
            accepted.put("statusUrl", "/api/wms/v1/warehouses/" + warehouseId + "/action-effects/" + effectId);
            HttpStatus status = HttpStatus.ACCEPTED;
            return ResponseEntity.status(status).body(accepted);
        }
    }

    @ExceptionHandler(WarehouseForbiddenException.class)
    ResponseEntity<Map<String, Object>> forbidden(WarehouseForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("WAREHOUSE_FORBIDDEN", "无权访问该仓"));
    }

    @ExceptionHandler(EffectProtocolException.class)
    ResponseEntity<Map<String, Object>> protocol(EffectProtocolException error) {
        HttpStatus status = switch (error.code()) {
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RECOVERY_PENDING" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(errorBody(error.code(), error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        String message = error.getMessage() == null ? "请求无效" : error.getMessage();
        String code = message.contains("企业") ? "WAREHOUSE_FORBIDDEN" : "INVALID_STATE";
        HttpStatus status = "WAREHOUSE_FORBIDDEN".equals(code) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private static String requireMatchingKey(String header, String body) {
        String key = EffectCodes.requireId("Idempotency-Key", header);
        if (body != null && !body.isBlank() && !key.equals(body)) {
            throw new IllegalArgumentException("clientOperationId必须与Idempotency-Key一致");
        }
        return key;
    }



    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId());
        body.put("retryable", "RECOVERY_PENDING".equals(code));
        return body;
    }
}
