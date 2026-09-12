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
        assertTrue(spec.path("paths").path("/api/wms/v1/skus/{skuId}/units").has("get"));
        assertTrue(spec.path("paths").path("/api/wms/v1/warehouses/{warehouseId}/lots").has("get"));
        assertTrue(spec.path("paths").has("/api/wms/v1/warehouses/{warehouseId}/action-effects"));
        assertFalse(spec.path("paths").has("/api/wms/v1/tcc/prepare"));
        assertFalse(spec.path("paths").has("/api/wms/v1/decisions"));
    }

    /** 任一新增公开 Controller 路由都必须同时登记 OpenAPI 和权限；变量名称可不同但路径结构必须一致。 */
    @Test
    void everyImplementedRouteHasContractAndRuntimeScope() throws Exception {
        var root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        var rules = java.nio.file.Files.readAllLines(root.resolve("wms-security/src/main/resources/wms-operation-scopes.tsv"))
                .stream().filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.split("\t")).collect(java.util.stream.Collectors.toMap(
                        parts -> parts[0] + " " + canonical(parts[1]), parts -> parts[2]));
        var contracts = new java.util.HashMap<String, String>();
        spec.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
            if (!path.getKey().startsWith("/api/wms/")) return;
            String scope = method.getValue().path("security").path(0).path("oidc").path(0).asText();
            String key = method.getKey().toUpperCase(java.util.Locale.ROOT) + " " + canonical(path.getKey());
            contracts.put(key, scope);
            assertEquals(scope, rules.get(key), "运行权限与契约不一致 " + key);
        }));
        for (String module : List.of("inbound", "outbound", "inventory", "fulfillment", "security")) {
            try (var files = java.nio.file.Files.walk(root.resolve("wms-" + module + "/src/main/java"))) {
                for (var file : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                    String source = java.nio.file.Files.readString(file);
                    var base = java.util.regex.Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)").matcher(source);
                    if (!base.find()) continue;
                    var methods = java.util.regex.Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\(\"([^\"]+)\"\\)").matcher(source);
                    while (methods.find()) {
                        String path = base.group(1) + methods.group(2);
                        if (!path.startsWith("/api/wms/")) continue;
                        String key = methods.group(1).toUpperCase(java.util.Locale.ROOT) + " " + canonical(path);
                        assertTrue(contracts.containsKey(key), "公开入口缺少作业权限契约 " + key);
                    }
                }
            }
        }
    }

    private static String canonical(String path) { return path.replaceAll("\\{[^}]+}", "{}"); }

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
        paths.add("/api/wms/v1/skus/{skuId}/units");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/locations");
        paths.add("/api/wms/v1/warehouses/{warehouseId}/lots");
        paths.add("/internal/wms/v1/warehouses/{warehouseId}/stock-commands");
        paths.add("/internal/wms/v1/warehouses/{warehouseId}/execution-permits");
        return paths;
    }
}
