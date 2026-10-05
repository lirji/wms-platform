package com.lrj.wms.driver.manifest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.RiskClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 只接受 execution-driver-manifest/v1，未知协议/action/risk 直接拒绝。 */
public final class DriverManifestLoader {
    public static final String PROTOCOL = "execution-driver-manifest/v1";
    private static final String CLASSPATH_DIR = "execution-driver-manifest/v1/";
    private final ObjectMapper mapper;

    public DriverManifestLoader() {
        this.mapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    }

    public List<DriverManifest> loadDefault() {
        return loadClasspathDirectory(CLASSPATH_DIR);
    }

    public List<DriverManifest> loadClasspathDirectory(String directory) {
        try {
            var classLoader = Thread.currentThread().getContextClassLoader();
            String normalized = directory.endsWith("/") ? directory : directory + "/";
            var resource = classLoader.getResource(normalized);
            if (resource == null) {
                resource =
                        classLoader.getResource(normalized.substring(0, normalized.length() - 1));
            }
            if (resource == null) {
                throw new ManifestValidationException("未找到 Driver Manifest 目录: " + directory);
            }
            Path root;
            try {
                root = Path.of(resource.toURI());
            } catch (Exception ignored) {
                root = null;
            }
            if (root == null || !Files.isDirectory(root)) {
                // 打包在 jar 内时不能按目录列举，回退到已知文件名。
                List<DriverManifest> packed = new ArrayList<>();
                for (String name : List.of("codex-local.json", "cursor-local.json")) {
                    try (InputStream in = classLoader.getResourceAsStream(normalized + name)) {
                        if (in != null) {
                            packed.add(parse(in));
                        }
                    }
                }
                if (packed.isEmpty()) {
                    throw new ManifestValidationException("classpath 中没有可加载的 Driver Manifest");
                }
                return List.copyOf(packed);
            }
            List<DriverManifest> manifests = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(path -> manifests.add(parse(path)));
            }
            if (manifests.isEmpty()) {
                throw new ManifestValidationException("Driver Manifest 目录为空: " + directory);
            }
            return List.copyOf(manifests);
        } catch (ManifestValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new ManifestValidationException("加载 Driver Manifest 失败: " + error.getMessage());
        }
    }

    public List<DriverManifest> loadDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new ManifestValidationException("Manifest 目录不存在: " + directory);
        }
        try (var stream = Files.list(directory)) {
            List<DriverManifest> manifests =
                    stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .map(this::parse)
                            .toList();
            if (manifests.isEmpty()) {
                throw new ManifestValidationException("Manifest 目录为空: " + directory);
            }
            return manifests;
        } catch (IOException error) {
            throw new ManifestValidationException("读取 Manifest 目录失败: " + error.getMessage());
        }
    }

    public DriverManifest parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException error) {
            throw new ManifestValidationException("无法读取 " + path + ": " + error.getMessage());
        }
    }

    public DriverManifest parse(String json) {
        try {
            return parseNode(mapper.readTree(json));
        } catch (IOException error) {
            throw new ManifestValidationException("Manifest JSON 非法: " + error.getMessage());
        }
    }

    public DriverManifest parse(InputStream in) {
        try {
            return parseNode(mapper.readTree(in));
        } catch (IOException error) {
            throw new ManifestValidationException("Manifest JSON 非法: " + error.getMessage());
        }
    }

    private DriverManifest parseNode(JsonNode node) {
        String protocol = text(node, "protocol");
        if (!PROTOCOL.equals(protocol)) {
            throw new ManifestValidationException("非法protocol: " + protocol);
        }
        String driverId = text(node, "driver_id");
        if (driverId.isBlank() || !driverId.matches("[a-z0-9-]+")) {
            throw new ManifestValidationException("driver_id 非法: " + driverId);
        }
        String displayName = text(node, "display_name");
        if (displayName.isBlank()) {
            throw new ManifestValidationException("display_name 不能为空");
        }
        DesiredStatus status;
        try {
            status = DesiredStatus.fromWire(text(node, "status"));
        } catch (IllegalArgumentException error) {
            throw new ManifestValidationException(error.getMessage());
        }
        int priority = node.path("priority").isNumber() ? node.get("priority").asInt() : 0;
        List<String> adapters = stringList(node.get("adapters"));
        if (adapters.isEmpty()) {
            throw new ManifestValidationException("adapters 不能为空");
        }
        for (String adapter : adapters) {
            if (!"native".equals(adapter)) {
                throw new ManifestValidationException("未知adapter: " + adapter);
            }
        }
        List<RiskClass> risks = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "risk_classes")) {
            try {
                risks.add(RiskClass.fromWire(item.asText()));
            } catch (IllegalArgumentException error) {
                throw new ManifestValidationException(error.getMessage());
            }
        }
        List<DriverAction> actions = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "capabilities")) {
            try {
                actions.add(DriverAction.fromWire(item.asText()));
            } catch (IllegalArgumentException error) {
                throw new ManifestValidationException(error.getMessage());
            }
        }
        JsonNode commandNode = node.get("command");
        if (commandNode == null || !commandNode.isObject()) {
            throw new ManifestValidationException("command 不能为空");
        }
        String executable = text(commandNode, "executable");
        if (executable.isBlank()) {
            throw new ManifestValidationException("command.executable 不能为空");
        }
        List<String> versionArgs =
                commandNode.has("version_args")
                        ? stringList(commandNode.get("version_args"))
                        : List.of("--version");
        String execSubcommand =
                commandNode.has("exec_subcommand")
                        ? commandNode.path("exec_subcommand").asText("")
                        : "exec";
        List<String> candidates = stringList(commandNode.get("candidates"));
        JsonNode timeoutNode = node.path("timeout");
        long defaultMs = timeoutNode.path("default_ms").asLong(120_000);
        long maxMs = timeoutNode.path("max_ms").asLong(600_000);
        return new DriverManifest(
                protocol,
                driverId,
                displayName,
                status,
                priority,
                adapters,
                List.copyOf(risks),
                List.copyOf(actions),
                new DriverManifest.CommandSpec(executable, versionArgs, execSubcommand, candidates),
                new DriverManifest.TimeoutSpec(defaultMs, maxMs));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static Iterable<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new ManifestValidationException(field + " 不能为空");
        }
        return value;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }
}
