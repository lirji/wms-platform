package com.lrj.wms.driver.policy;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 写操作与本地命令必须过策略；没有授权体系时破坏性命令一律拒绝。 */
public final class DriverPolicy {
    private static final List<Pattern> DESTRUCTIVE =
            List.of(
                    Pattern.compile("rm\\s+-rf\\s+(/|\\*)"),
                    Pattern.compile("git\\s+reset\\s+--hard"),
                    Pattern.compile("git\\s+clean\\s+-fdx"),
                    Pattern.compile("drop\\s+database", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\bshutdown\\b"),
                    Pattern.compile("\\breboot\\b"),
                    Pattern.compile("mkfs\\."),
                    Pattern.compile("dd\\s+if="));

    private final Path workspaceRoot;

    public DriverPolicy(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public void assertExecutable(RegisteredDriver driver, ExecutionRequest request) {
        if (driver.runtimeStatus() == RuntimeStatus.DISABLED) {
            throw new DriverException(
                    DriverErrorCodes.DISABLED, "Driver 已禁用", Map.of("driverId", driver.driverId()));
        }
        if (driver.runtimeStatus() != RuntimeStatus.ACTIVE) {
            throw new DriverException(
                    DriverErrorCodes.UNAVAILABLE,
                    "Driver 当前不可执行",
                    Map.of(
                            "driverId",
                            driver.driverId(),
                            "runtimeStatus",
                            driver.runtimeStatus().name()));
        }
        List<DriverAction> missing =
                request.requiredActions().stream()
                        .filter(action -> !driver.manifest().capabilities().contains(action))
                        .toList();
        if (!missing.isEmpty()) {
            DriverAction first = missing.getFirst();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("driverId", driver.driverId());
            details.put("requiredAction", first.wire());
            details.put(
                    "supportedActions",
                    driver.manifest().capabilities().stream().map(DriverAction::wire).toList());
            throw new DriverException(
                    DriverErrorCodes.CAPABILITY_NOT_SUPPORTED, "Driver 不支持所需 action", details);
        }
        if (!driver.manifest().riskClasses().contains(request.riskClass())) {
            throw new DriverException(
                    DriverErrorCodes.POLICY_DENIED,
                    "riskClass 不被该 Driver 接受",
                    Map.of("driverId", driver.driverId(), "riskClass", request.riskClass().wire()));
        }
        Path cwd = request.workingDirectory().toAbsolutePath().normalize();
        Path root = workspaceRoot;
        try {
            cwd = cwd.toRealPath();
            root = workspaceRoot.toRealPath();
        } catch (java.io.IOException ignored) {
            // 目标不存在时仍用 normalize 后的绝对路径，防止 ../ 逃逸。
        }
        if (!cwd.startsWith(root)) {
            throw new DriverException(
                    DriverErrorCodes.POLICY_DENIED,
                    "工作目录超出 workspace 边界",
                    Map.of("workingDirectory", cwd.toString(), "workspaceRoot", root.toString()));
        }
        String haystack =
                (request.instruction() + " " + String.join(" ", request.environment().values()))
                        .toLowerCase(Locale.ROOT);
        for (Pattern pattern : DESTRUCTIVE) {
            if (pattern.matcher(haystack).find()) {
                throw new DriverException(
                        DriverErrorCodes.POLICY_DENIED,
                        "拒绝明显破坏性命令",
                        Map.of("driverId", driver.driverId(), "reason", "command_safety"));
            }
        }
        if (request.timeout() != null
                && (request.timeout().isZero() || request.timeout().isNegative())) {
            throw new DriverException(
                    DriverErrorCodes.POLICY_DENIED,
                    "timeout 必须为正",
                    Map.of("driverId", driver.driverId()));
        }
        long max = driver.manifest().timeout().maxMs();
        if (request.timeout() != null && request.timeout().toMillis() > max) {
            throw new DriverException(
                    DriverErrorCodes.POLICY_DENIED, "timeout 超过 Driver 上限", Map.of("maxMs", max));
        }
    }
}
