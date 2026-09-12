package com.lrj.wms.security;

import com.lrj.wms.runtime.web.AdmissionGate;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/** 验签后按可信企业限流；无企业令牌仍由权限边界拒绝，不能选择他人配额。 */
final class TenantAdmissionFilter extends OncePerRequestFilter {
    private final AdmissionGate gate;
    TenantAdmissionFilter(AdmissionGate gate) { this.gate = gate; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/wms/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String tenant;
        try { tenant = WmsJwtAuthorities.enterpriseId(jwt); }
        catch (IllegalArgumentException error) { response.sendError(403); return; }
        String key = request.getHeader("Idempotency-Key");
        String limit = request.getParameter("limit");
        String cursor = request.getParameter("cursor");
        boolean invalid = key != null && (key.isBlank() || key.length() > 64)
                || cursor != null && cursor.length() > 2048;
        if (limit != null) {
            try { int size = Integer.parseInt(limit); invalid |= size < 1 || size > 200; }
            catch (NumberFormatException error) { invalid = true; }
        }
        if (invalid || request.getContentLengthLong() > com.lrj.wms.runtime.web.BoundedRequestBody.MAX_BYTES) {
            response.setStatus(invalid ? 400 : 413);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"INVALID_ARGUMENT\",\"message\":\"请求超出约束\",\"retryable\":false,\"requestId\":\""
                    + com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId() + "\"}");
            return;
        }
        var permit = gate.acquire(tenant);
        if (permit == null) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求超过当前配额，请稍后重试\","
                    + "\"retryable\":true,\"requestId\":\"" + com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId() + "\"}");
            return;
        }
        try { chain.doFilter(new com.lrj.wms.runtime.web.BoundedRequestBody(request), response); }
        finally {
            if (request.isAsyncStarted()) {
                try { request.getAsyncContext().addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent event) { permit.close(); }
                    public void onTimeout(AsyncEvent event) { permit.close(); }
                    public void onError(AsyncEvent event) { permit.close(); }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                }); } catch (IllegalStateException alreadyCompleted) { permit.close(); }
            } else { permit.close(); }
        }
    }
}
