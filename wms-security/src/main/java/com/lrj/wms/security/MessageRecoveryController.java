package com.lrj.wms.security;

import com.lrj.wms.runtime.messaging.MessageRecoveryException;
import com.lrj.wms.runtime.messaging.MessageRecoveryService;
import com.lrj.wms.runtime.observability.RequestCorrelationFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 共享运维入口只绑定本服务恢复用例，JWT范围与作业权限都不可省略。 */
@RestController
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}/message-queues/{queue}")
public final class MessageRecoveryController {
    private final MessageRecoveryService recovery;
    public MessageRecoveryController(MessageRecoveryService recovery) { this.recovery = recovery; }

    /** 元数据列表不包含原始消息，游标和SQL都绑定授权范围。 */
    @GetMapping("/messages")
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String queue, @RequestParam(defaultValue = "ISOLATED") String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        WmsJwtAuthorities.requireScope(jwt, "messaging.read");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        return recovery.list(WmsJwtAuthorities.enterpriseId(jwt), warehouseId, queue, status, limit, cursor);
    }

    /** 重试受理只代表重新排队；预算、事件内容与实际业务完成各自有独立含义。 */
    @PostMapping("/messages/{messageId}/retries")
    public ResponseEntity<Map<String, Object>> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId,
            @PathVariable String queue, @PathVariable String messageId, @RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody RetryRequest body) {
        WmsJwtAuthorities.requireScope(jwt, "messaging.recover");
        WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
        return ResponseEntity.accepted().body(recovery.retry(WmsJwtAuthorities.enterpriseId(jwt), warehouseId,
                queue, messageId, commandId, body.expectedEpoch(), body.reason(), jwt.getSubject()));
    }

    /** 缺版本不能默认零，必须与运维查询得到的原代际一致。 */
    public record RetryRequest(@NotNull @Min(0) Long expectedEpoch, @NotBlank @Size(max = 500) String reason) { }

    @ExceptionHandler(MessageRecoveryException.class)
    ResponseEntity<Map<String, Object>> conflict(MessageRecoveryException failure) {
        int status = failure.code().startsWith("INVALID_") ? 400 : failure.code().equals("MESSAGE_NOT_FOUND") ? 404 : 409;
        return ResponseEntity.status(status).body(Map.of("code", failure.code(), "message", "消息恢复条件不满足，请核对范围、状态与原始事实",
                "retryable", false, "requestId", RequestCorrelationFilter.currentId()));
    }

    @ExceptionHandler({WarehouseForbiddenException.class, ScopeForbiddenException.class})
    ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code", "MESSAGE_RECOVERY_FORBIDDEN", "message", "缺少消息操作权限或仓库范围",
                "retryable", false, "requestId", RequestCorrelationFilter.currentId()));
    }

    @ExceptionHandler(org.apache.ibatis.exceptions.PersistenceException.class)
    ResponseEntity<Map<String, Object>> database(Exception failure) {
        return new com.lrj.wms.runtime.web.RuntimeErrors().database(failure);
    }
}
