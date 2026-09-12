package com.lrj.wms.runtime.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

/** 线程复用不能串请求身份，异常返回也须保留响应关联头。 */
class RequestCorrelationTest {
    @Test
    void bindsAndRestoresContextEvenWhenRequestFails() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-one");
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "parent");
        try {
            assertThrows(jakarta.servlet.ServletException.class, () -> new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                assertEquals("request-one", RequestCorrelationFilter.currentId());
                throw new jakarta.servlet.ServletException("测试失败路径");
            }));
            assertEquals("parent", MDC.get("requestId"));
            assertEquals("request-one", response.getHeader("X-Request-Id"));
        } finally { MDC.clear(); }
    }
}
