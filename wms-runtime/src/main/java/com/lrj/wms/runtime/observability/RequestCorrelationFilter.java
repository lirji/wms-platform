package com.lrj.wms.runtime.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** 关联标识贯穿HTTP响应与日志；不记录请求正文、令牌，线程复用前恢复MDC。 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static String currentId() {
        String value = MDC.get("requestId");
        return value == null ? UUID.randomUUID().toString() : value;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String id = incoming != null && incoming.matches("[A-Za-z0-9._:-]{1,64}") ? incoming : UUID.randomUUID().toString();
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
