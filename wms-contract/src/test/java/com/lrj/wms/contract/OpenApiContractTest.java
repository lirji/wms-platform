package com.lrj.wms.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 校验已落实的 OpenAPI：路径、幂等头、错误、分页、数量字符串，且不含密钥。 */
class OpenApiContractTest {
    private static JsonNode spec;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = OpenApiContractTest.class.getResourceAsStream("/openapi/wms-v1.yaml")) {
            assertNotNull(in, "缺少 openapi/wms-v1.yaml");
            spec = new YAMLMapper().readTree(in);
        }
    }

    @Test
    void documentIsOpenApi31WithoutSecrets() {
        assertEquals("3.1.0", spec.path("openapi").asText());
        String raw = spec.toString().toLowerCase();
        assertFalse(raw.contains("client_secret"));
        assertFalse(raw.contains("client-secret"));
        assertEquals("openIdConnect", spec.path("components").path("securitySchemes").path("oidc").path("type").asText());
    }

    @Test
    void requiredDesignPathsExist() {
        JsonNode paths = spec.path("paths");
        for (String path : requiredPaths()) {
            assertTrue(paths.has(path), "缺少路径 " + path);
        }
    }

    @Test
    void writeOperationsRequireIdempotencyKey() {
        spec.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
            String verb = method.getKey();
            if (!Set.of("post", "put", "patch").contains(verb)) {
                return;
            }
            boolean found = false;
            for (JsonNode parameter : method.getValue().path("parameters")) {
                if ("#/components/parameters/IdempotencyKey".equals(parameter.path("$ref").asText())) {
                    found = true;
                }
            }
            assertTrue(found, path.getKey() + " " + verb + " 缺少 Idempotency-Key");
        }));
    }

    @Test
    void errorAndPaginationAndQuantityAreDefined() {
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode error = schemas.path("ErrorResponse");
        for (String field : List.of("code", "message", "requestId", "retryable")) {
            assertTrue(hasRequired(error, field), "ErrorResponse 缺少 " + field);
        }
        assertTrue(error.path("properties").path("code").path("enum").isArray());
        assertEquals("string", schemas.path("Quantity").path("type").asText());
        JsonNode page = schemas.path("CursorPage");
        assertTrue(hasRequired(page, "items"));
        assertEquals(200, page.path("properties").path("limit").path("maximum").asInt());
        String listSchema = spec.path("paths").path("/api/wms/v1/skus").path("get")
                .path("responses").path("200").path("content").path("application/json")
                .path("schema").path("$ref").asText();
        assertEquals("#/components/schemas/CursorPage", listSchema);
        assertTrue(spec.path("paths").has("/api/wms/v1/skus"));
        assertTrue(spec.path("paths").has("/api/wms/v1/warehouses/{warehouseId}/action-effects"));
        assertFalse(spec.path("paths").has("/api/wms/v1/tcc/prepare"));
        assertFalse(spec.path("paths").has("/api/wms/v1/decisions"));
    }

    private static boolean hasRequired(JsonNode schema, String field) {
        for (JsonNode item : schema.path("required")) {
            if (field.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> requiredPaths() {
        List<String> paths = new ArrayList<>();
        paths.add("/api/wms/v1/fulfillments");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/inbound-orders");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/inbound-orders/{inboundOrderId}/receipts");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/putaways");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/picks");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/shipments");
        paths.add("/api/wms/v1/transfers");
        paths.add("/api/wms/v1/inventory");
        paths.add("/api/wms/v1/operations/{operationId}");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/action-effects/{effectId}/execution-attempts");
        paths.add("/api/wms/v1/skus");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/locations");
        paths.add("/internal/wms/v1/warehouses/{warehouseId}/stock-commands");
        paths.add("/internal/wms/v1/warehouses/{warehouseId}/execution-permits");
        return paths;
    }
}
