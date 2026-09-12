package com.lrj.wms.security;

import com.lrj.wms.runtime.web.AdmissionBudget;
import com.lrj.wms.runtime.web.AdmissionGate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

/** 入口以验签后的企业为配额身份，不能通过换请求头绕过预算。 */
class TenantAdmissionFilterTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void rejectsInvalidLimitAndEmptyIdempotencyKeyBeforeHandler() throws Exception {
        authenticate("ENT");
        var filter = new TenantAdmissionFilter(new AdmissionGate(new AdmissionBudget(2, 1, 100, 100)));
        var request = new MockHttpServletRequest("GET", "/api/wms/v1/inventory");
        request.addParameter("limit", "201");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("非法分页不能进入用例"));
        assertEquals(400, response.getStatus());
        request = new MockHttpServletRequest("POST", "/api/wms/v1/warehouses/WH/inbound-orders");
        request.addHeader("Idempotency-Key", " "); response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("空幂等键不能进入用例"));
        assertEquals(400, response.getStatus());
    }
    @Test void releaseDoesNotResetRateAndOtherTenantCanProceed() throws Exception {
        var filter = new TenantAdmissionFilter(new AdmissionGate(new AdmissionBudget(2, 1, 100, 1)));
        AtomicInteger calls = new AtomicInteger();
        authenticate("A");
        var first = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/wms/v1/skus"), first, (req, res) -> calls.incrementAndGet());
        var denied = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/wms/v1/skus"), denied, (req, res) -> fail("同租户应限流"));
        assertEquals(429, denied.getStatus()); assertEquals("1", denied.getHeader("Retry-After"));
        authenticate("B");
        var other = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/wms/v1/skus"), other, (req, res) -> calls.incrementAndGet());
        assertEquals(2, calls.get());
    }
    private static void authenticate(String tenant) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("actor").claim("enterprise_id", tenant).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
