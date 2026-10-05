package com.lrj.wms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 根据已提交契约逐路由校验作业权限；新增但未登记的公开入口默认拒绝。仓与对象权限仍由业务边界校验。 */
final class OperationScopeFilter extends OncePerRequestFilter {
    private final List<Rule> rules = loadRules();
    private final WmsCentralAuthorization central;

    OperationScopeFilter() {
        this(null);
    }

    OperationScopeFilter(WmsCentralAuthorization central) {
        this.central = central;
    }

    record Rule(String method, PathPattern path, String scope) {}

    static List<Rule> loadRules() {
        var input = OperationScopeFilter.class.getResourceAsStream("/wms-operation-scopes.tsv");
        if (input == null) throw new IllegalStateException("缺少公开作业权限契约");
        try (var reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(
                            line -> {
                                String[] parts = line.split("\t", -1);
                                if (parts.length != 3 || parts[2].isBlank())
                                    throw new IllegalStateException("作业权限契约无效");
                                return new Rule(
                                        parts[0],
                                        new PathPatternParser().parse(parts[1]),
                                        parts[2]);
                            })
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("不能加载作业权限契约", error);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI()
                .substring(request.getContextPath().length())
                .startsWith("/api/wms/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && "/api/wms/v1/me/access".equals(uri)) {
            chain.doFilter(request, response);
            return;
        }
        if (central != null) {
            try {
                var matched = central.bindings.route(request.getMethod(), uri);
                try (var authorized = central.authorize(jwt, matched)) {
                    var extracted = matched.path().matchAndExtract(PathContainer.parsePath(uri));
                    String warehouse =
                            extracted == null
                                    ? null
                                    : extracted.getUriVariables().get("warehouseId");
                    if (warehouse != null) authorized.requireWarehouse(warehouse);
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new org.springframework.security.oauth2.server.resource
                                            .authentication.JwtAuthenticationToken(
                                            authorized,
                                            List.of(
                                                    new org.springframework.security.core.authority
                                                            .SimpleGrantedAuthority(
                                                            "CENTRAL_WMS"))));
                    response.setHeader("Cache-Control", "no-store");
                    chain.doFilter(request, response);
                }
            } catch (CentralAuthorizationException failure) {
                if (response.isCommitted()) throw failure;
                WmsCentralErrors.write(response, failure);
            } catch (WarehouseForbiddenException rejected) {
                WmsCentralErrors.write(
                        response,
                        new CentralAuthorizationException(
                                CentralAuthorizationException.Reason.DENIED));
            } finally {
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            return;
        }
        String method = "HEAD".equals(request.getMethod()) ? "GET" : request.getMethod();
        var path =
                PathContainer.parsePath(
                        request.getRequestURI().substring(request.getContextPath().length()));
        var scopes = WmsJwtAuthorities.operationScopes(jwt);
        boolean allowed =
                rules.stream()
                        .anyMatch(
                                rule ->
                                        rule.method().equals(method)
                                                && rule.path().matches(path)
                                                && scopes.contains(rule.scope()));
        if (!allowed) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            "{\"code\":\"SCOPE_FORBIDDEN\",\"message\":\"缺少作业权限\",\"retryable\":false,\"requestId\":\""
                                    + com.lrj.wms.runtime.observability.RequestCorrelationFilter
                                            .currentId()
                                    + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
