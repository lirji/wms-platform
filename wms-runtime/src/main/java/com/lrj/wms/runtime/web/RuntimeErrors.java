package com.lrj.wms.runtime.web;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 共享边界只翻译明确的运行时错误，不改变领域错误语义。 */
@RestControllerAdvice
public class RuntimeErrors {
    @ExceptionHandler(com.lrj.wms.runtime.command.CommandConflictException.class)
    public ResponseEntity<Map<String, Object>> commandConflict() {
        return ResponseEntity.status(409).body(Map.of("code", "IDEMPOTENCY_PAYLOAD_MISMATCH", "message", "同一命令或事实身份的内容不一致",
                "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId(), "retryable", false));
    }

    @ExceptionHandler(com.lrj.wms.runtime.cache.QueryCacheBusyException.class)
    public ResponseEntity<Map<String, Object>> cacheBusy() {
        return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of("code", "QUERY_OVERLOADED",
                "message", "查询繁忙，请稍后重试", "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId(), "retryable", true));
    }

    /** 不返回原始解析异常，避免泄漏请求内容、内部类名和数据库详情。 */
    @ExceptionHandler({com.lrj.wms.runtime.command.InvalidCommandKeyException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, java.time.DateTimeException.class})
    public ResponseEntity<Map<String, Object>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ARGUMENT", "message", "请求字段缺失、类型或范围不合法",
                "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId(), "retryable", false));
    }

    @ExceptionHandler({java.sql.SQLException.class, org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, Object>> database(Exception error) {
        boolean duplicate = com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(error);
        return ResponseEntity.status(duplicate ? 409 : 503).body(Map.of("code", duplicate ? "DUPLICATE_DOCUMENT" : "DATABASE_UNAVAILABLE",
                "message", duplicate ? "记录已存在" : "持久化服务暂不可用", "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId(), "retryable", !duplicate));
    }

    @ExceptionHandler(InvalidPageException.class)
    public ResponseEntity<Map<String, Object>> invalidPage(InvalidPageException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PAGE", "message", error.getMessage(),
                "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId(), "retryable", false));
    }
}
