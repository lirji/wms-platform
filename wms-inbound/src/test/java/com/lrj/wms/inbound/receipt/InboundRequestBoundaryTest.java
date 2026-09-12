package com.lrj.wms.inbound.receipt;

import com.lrj.wms.runtime.web.RuntimeErrors;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 边界回归：错误输入不触碰数据库，数据库故障不能伪装重复单据。 */
class InboundRequestBoundaryTest {
    private SqlSessionFactory sessions;
    private MockMvc mvc;
    private static final String URL = "/api/wms/v1/warehouses/WH/inbound-orders";
    private static final String VALID = """
            {"sourceSystem":"ERP","externalNo":"ASN","ownerId":"OWNER",
             "lines":[{"externalLineId":"L1","skuId":"SKU","expectedQty":"1"}]}
            """;
    @BeforeEach void setup() {
        sessions = mock(SqlSessionFactory.class);
        mvc = MockMvcBuilders.standaloneSetup(new InboundWorkbenchController(sessions, false))
                .setControllerAdvice(new RuntimeErrors())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("actor")
                .claim("enterprise_id", "ENT").claim("warehouses", List.of("WH"))
                .claim("scope", List.of("inbound.create", "inbound.read", "inbound.receive")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void malformedQuantityMissingRequiredFieldAndOversizedPageAreRejectedBeforeSql() throws Exception {
        for (String body : List.of(VALID.replace("\"1\"", "\"bad-number\""), VALID.replace("\"OWNER\"", "\"\""),
                VALID.replace("\"1\"", "\"0.0000001\""))) {
            mvc.perform(post(URL).header("Idempotency-Key", "CMD").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(get(URL).param("limit", "201")).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }
    @Test void databaseFailureRemainsRetryable503AndRealDuplicateIs409() throws Exception {
        SqlSession session = mock(SqlSession.class); InboundReceiptMapper mapper = mock(InboundReceiptMapper.class);
        when(sessions.openSession(false)).thenReturn(session); when(session.getMapper(InboundReceiptMapper.class)).thenReturn(mapper);
        when(mapper.insertOrder(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new PersistenceException(new SQLException("internal database host", "08001", 0)))
                .thenThrow(new PersistenceException(new SQLException("duplicate key", "23000", 1062)));
        mvc.perform(post(URL).header("Idempotency-Key", "CMD").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
        mvc.perform(post(URL).header("Idempotency-Key", "CMD").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"));
        verify(session, never()).commit();
    }
}
