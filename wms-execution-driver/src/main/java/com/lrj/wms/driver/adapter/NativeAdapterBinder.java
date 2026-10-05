package com.lrj.wms.driver.adapter;

import com.lrj.wms.driver.manifest.DriverManifest;
import com.lrj.wms.driver.process.ProcessExecutor;

import java.nio.file.Path;
import java.util.Map;

/** 按 driver_id 绑定 native adapter，避免把 Codex/Cursor 参数泄漏到核心层。 */
public final class NativeAdapterBinder {
    public static final String KIND = "native";

    private NativeAdapterBinder() {}

    public static DriverAdapter bind(
            DriverManifest manifest,
            ProcessExecutor processes,
            Map<String, Path> executableOverrides) {
        if (manifest == null || !manifest.adapters().contains(KIND)) {
            return null;
        }
        Path override =
                executableOverrides == null ? null : executableOverrides.get(manifest.driverId());
        return switch (manifest.driverId()) {
            case "cursor-local" -> new NativeCursorAdapter(processes, override);
            case "codex-local" -> new NativeCodexAdapter(processes, override);
            default -> null;
        };
    }
}
