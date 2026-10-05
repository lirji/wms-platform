package com.lrj.wms.driver.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 从指定环境变量或 PATH 解析可执行文件，禁止把本机路径写死进核心层。 */
public final class ExecutableResolver {
    public static final String CODEX_CLI_ENV = "CODEX_CLI";
    public static final String WMS_CODEX_CLI_ENV = "WMS_DRIVER_CODEX_CLI";
    public static final String CURSOR_CLI_ENV = "CURSOR_CLI";
    public static final String WMS_CURSOR_CLI_ENV = "WMS_DRIVER_CURSOR_CLI";

    private ExecutableResolver() {}

    public static Optional<Path> resolve(String executableName) {
        return resolve(List.of(executableName), CODEX_CLI_ENV, WMS_CODEX_CLI_ENV);
    }

    public static Optional<Path> resolve(List<String> names, String... envKeys) {
        Path explicit = firstExisting(envKeys);
        if (explicit != null) {
            return Optional.of(explicit);
        }
        Set<String> ordered = new LinkedHashSet<>();
        if (names != null) {
            names.stream().filter(name -> name != null && !name.isBlank()).forEach(ordered::add);
        }
        for (String executableName : ordered) {
            Path asPath = Path.of(executableName);
            if (asPath.isAbsolute() && Files.isExecutable(asPath) && Files.isRegularFile(asPath)) {
                return Optional.of(asPath);
            }
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                continue;
            }
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, executableName);
                if (Files.isExecutable(candidate) && Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Path firstExisting(String... envKeys) {
        if (envKeys == null) {
            return null;
        }
        for (String key : envKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            Path path = Path.of(value.trim());
            if (Files.isExecutable(path) && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    public static List<String> names(String primary, List<String> candidates) {
        List<String> names = new ArrayList<>();
        if (primary != null && !primary.isBlank()) {
            names.add(primary);
        }
        if (candidates != null) {
            names.addAll(candidates);
        }
        return names;
    }

    public static boolean looksLikeWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }
}
