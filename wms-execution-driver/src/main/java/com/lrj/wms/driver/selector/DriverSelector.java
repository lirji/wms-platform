package com.lrj.wms.driver.selector;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.DriverRegistry;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Driver 选择集中在此，禁止散落到业务代码。 */
public final class DriverSelector {
    private final DriverRegistry registry;

    public DriverSelector(DriverRegistry registry) {
        this.registry = registry;
    }

    public Optional<RegisteredDriver> find(SelectionQuery query) {
        List<RegisteredDriver> eligible =
                registry.list().stream()
                        .filter(driver -> driver.runtimeStatus() == RuntimeStatus.ACTIVE)
                        .filter(RegisteredDriver::healthy)
                        .filter(driver -> driver.adapter() != null)
                        .filter(
                                driver ->
                                        driver.manifest().riskClasses().contains(query.riskClass()))
                        .filter(
                                driver ->
                                        driver.manifest()
                                                .capabilities()
                                                .containsAll(query.requiredActions()))
                        .sorted(
                                Comparator.comparingInt(
                                                (RegisteredDriver driver) ->
                                                        driver.manifest().priority())
                                        .reversed()
                                        .thenComparing(RegisteredDriver::driverId))
                        .toList();
        if (query.preferredDriver() != null && !query.preferredDriver().isBlank()) {
            return eligible.stream()
                    .filter(driver -> driver.driverId().equals(query.preferredDriver()))
                    .findFirst();
        }
        return eligible.stream().findFirst();
    }

    public RegisteredDriver require(SelectionQuery query) {
        Optional<RegisteredDriver> selected = find(query);
        if (selected.isPresent()) {
            return selected.get();
        }
        if (query.preferredDriver() != null && !query.preferredDriver().isBlank()) {
            Optional<RegisteredDriver> preferred = registry.find(query.preferredDriver());
            if (preferred.isPresent()) {
                throw rejectPreferred(preferred.get(), query);
            }
        }
        throw new DriverException(
                DriverErrorCodes.SELECTION_FAILED,
                "没有可用 Driver",
                Map.of(
                        "requiredActions",
                                query.requiredActions().stream().map(DriverAction::wire).toList(),
                        "riskClass", query.riskClass().wire(),
                        "preferredDriver",
                                query.preferredDriver() == null ? "" : query.preferredDriver()));
    }

    private static DriverException rejectPreferred(RegisteredDriver driver, SelectionQuery query) {
        if (driver.runtimeStatus() == RuntimeStatus.DISABLED) {
            return new DriverException(
                    DriverErrorCodes.DISABLED, "Driver 已禁用", Map.of("driverId", driver.driverId()));
        }
        if (driver.runtimeStatus() != RuntimeStatus.ACTIVE
                || !driver.healthy()
                || driver.adapter() == null) {
            return new DriverException(
                    DriverErrorCodes.UNAVAILABLE,
                    "Driver 当前不可执行",
                    Map.of(
                            "driverId",
                            driver.driverId(),
                            "runtimeStatus",
                            driver.runtimeStatus().name()));
        }
        List<DriverAction> missing =
                query.requiredActions().stream()
                        .filter(action -> !driver.manifest().capabilities().contains(action))
                        .toList();
        if (!missing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("driverId", driver.driverId());
            details.put("requiredAction", missing.getFirst().wire());
            details.put(
                    "supportedActions",
                    driver.manifest().capabilities().stream().map(DriverAction::wire).toList());
            return new DriverException(
                    DriverErrorCodes.CAPABILITY_NOT_SUPPORTED, "Driver 不支持所需 action", details);
        }
        if (!driver.manifest().riskClasses().contains(query.riskClass())) {
            return new DriverException(
                    DriverErrorCodes.POLICY_DENIED,
                    "riskClass 不被该 Driver 接受",
                    Map.of("driverId", driver.driverId(), "riskClass", query.riskClass().wire()));
        }
        return new DriverException(
                DriverErrorCodes.SELECTION_FAILED,
                "没有可用 Driver",
                Map.of("driverId", driver.driverId()));
    }
}
