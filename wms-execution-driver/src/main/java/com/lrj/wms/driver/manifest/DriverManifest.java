package com.lrj.wms.driver.manifest;

import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.RiskClass;

import java.util.List;

public record DriverManifest(
        String protocol,
        String driverId,
        String displayName,
        DesiredStatus status,
        int priority,
        List<String> adapters,
        List<RiskClass> riskClasses,
        List<DriverAction> capabilities,
        CommandSpec command,
        TimeoutSpec timeout) {

    public record CommandSpec(
            String executable,
            List<String> versionArgs,
            String execSubcommand,
            List<String> candidates) {
        public CommandSpec {
            versionArgs =
                    versionArgs == null || versionArgs.isEmpty()
                            ? List.of("--version")
                            : List.copyOf(versionArgs);
            if (execSubcommand == null) {
                execSubcommand = "exec";
            }
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public CommandSpec(String executable, List<String> versionArgs, String execSubcommand) {
            this(executable, versionArgs, execSubcommand, List.of());
        }
    }

    public record TimeoutSpec(long defaultMs, long maxMs) {
        public TimeoutSpec {
            if (defaultMs <= 0) {
                defaultMs = 120_000;
            }
            if (maxMs <= 0) {
                maxMs = 600_000;
            }
        }
    }
}
