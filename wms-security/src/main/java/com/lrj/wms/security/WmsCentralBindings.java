package com.lrj.wms.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 消费Owner同源生成的中央绑定；未知路由或与原契约不一致时不能默认放行。 */
final class WmsCentralBindings {
    static final String WAREHOUSE = "wms_warehouse";
    static final String ENTERPRISE = "wms_enterprise";

    record Binding(
            String method,
            PathPattern path,
            String legacy,
            String resource,
            String capability,
            String slice) {}

    private final List<Binding> bindings;
    private final Map<String, String> capabilities;
    private final Map<String, Set<String>> menuRequirements;
    private final com.fasterxml.jackson.databind.node.ObjectNode permissionCatalog;

    WmsCentralBindings() {
        try {
            var input = getClass().getResourceAsStream("/wms-central-operation-bindings.tsv");
            if (input == null) throw new IllegalStateException();
            Map<String, Binding> routes = new LinkedHashMap<>();
            Map<String, String> meanings = new LinkedHashMap<>();
            try (var reader =
                    new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line :
                        reader.lines()
                                .filter(value -> !value.isBlank() && !value.startsWith("#"))
                                .toList()) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 6
                            || !List.of(WAREHOUSE, ENTERPRISE).contains(fields[3])
                            || !fields[4].matches("wms\\.[a-z][a-z0-9._-]{1,95}"))
                        throw new IllegalStateException();
                    Binding binding =
                            new Binding(
                                    fields[0],
                                    new PathPatternParser().parse(fields[1]),
                                    fields[2],
                                    fields[3],
                                    fields[4],
                                    fields[5]);
                    if (routes.put(fields[0] + " " + fields[1], binding) != null)
                        throw new IllegalStateException();
                    String previous = meanings.put(fields[2] + ":" + fields[3], fields[4]);
                    if (previous != null && !previous.equals(fields[4]))
                        throw new IllegalStateException();
                }
            }
            var legacy = OperationScopeFilter.loadRules();
            if (routes.size() != legacy.size() || routes.size() > 500)
                throw new IllegalStateException();
            for (var rule : legacy) {
                Binding binding = routes.get(rule.method() + " " + rule.path().getPatternString());
                if (binding == null || !binding.legacy().equals(rule.scope()))
                    throw new IllegalStateException();
            }
            bindings = List.copyOf(routes.values());
            capabilities = Map.copyOf(meanings);
            var requirements = new HashMap<String, Set<String>>();
            try (var catalog = getClass().getResourceAsStream("/wms-central-catalog.json")) {
                if (catalog == null) throw new IllegalStateException();
                var source =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                new ObjectMapper().readTree(catalog);
                if (!"wms".equals(source.path("application").asText())
                        || source.path("menus").size() > 100) {
                    throw new IllegalStateException();
                }
                for (var menu : source.path("menus")) {
                    var required = new HashSet<String>();
                    for (var code : menu.path("any_of")) {
                        if (!capabilities.containsValue(code.asText()))
                            throw new IllegalStateException();
                        required.add(code.asText());
                    }
                    if (requirements.put(menu.path("code").asText(), Set.copyOf(required)) != null)
                        throw new IllegalStateException();
                }
                // 与Auth CatalogManifest权限摘要保持相同归一化；显示字段不成为授权条件。
                var normalized = new ObjectMapper().createArrayNode();
                var menus = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                source.path("menus").forEach(menus::add);
                menus.sort(java.util.Comparator.comparing(menu -> menu.path("code").asText()));
                for (var menu : menus) {
                    var value = (com.fasterxml.jackson.databind.node.ObjectNode) menu.deepCopy();
                    value.remove(List.of("label", "position"));
                    normalized.add(value);
                }
                source.set("menus", normalized);
                permissionCatalog = source;
            }
            menuRequirements = Map.copyOf(requirements);
        } catch (Exception failure) {
            throw new IllegalStateException("WMS中央操作绑定缺失或漂移");
        }
    }

    Binding route(String method, String path) {
        String effectiveMethod = "HEAD".equals(method) ? "GET" : method;
        var container = PathContainer.parsePath(path);
        return bindings.stream()
                .filter(
                        binding ->
                                effectiveMethod.equals(binding.method())
                                        && binding.path().matches(container))
                .findFirst()
                .orElseThrow(
                        () ->
                                new CentralAuthorizationException(
                                        CentralAuthorizationException.Reason.DENIED));
    }

    String capability(String legacy, String resource) {
        String value = capabilities.get(legacy + ":" + resource);
        if (value == null)
            throw new CentralAuthorizationException(CentralAuthorizationException.Reason.DENIED);
        return value;
    }

    List<Binding> all() {
        return bindings;
    }

    Set<String> requiredForMenu(String code) {
        var required = menuRequirements.get(code);
        if (required == null)
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        return required;
    }

    String contentHash(long version) {
        try {
            var current = permissionCatalog.deepCopy();
            current.put("manifest_version", version);
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(new ObjectMapper().writeValueAsBytes(current)));
        } catch (Exception failure) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        }
    }
}
