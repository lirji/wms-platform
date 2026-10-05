package com.lrj.wms.security;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/** HTTP过滤器和Controller共享稳定失败边界，不公开内部身份、Token或依赖异常。 */
@RestControllerAdvice
final class WmsCentralErrors {
    @ExceptionHandler(CentralAuthorizationException.class)
    ResponseEntity<?> central(CentralAuthorizationException failure) {
        return ResponseEntity.status(failure.reason.status)
                .header("Cache-Control", "no-store")
                .body(body(failure));
    }

    static Map<String, Object> body(CentralAuthorizationException failure) {
        return Map.of(
                "code",
                failure.reason.code,
                "message",
                failure.reason.message,
                "retryable",
                failure.reason.retryable,
                "requestId",
                com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId());
    }

    static void write(HttpServletResponse response, CentralAuthorizationException failure)
            throws IOException {
        response.setStatus(failure.reason.status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValue(response.getOutputStream(), body(failure));
    }
}
