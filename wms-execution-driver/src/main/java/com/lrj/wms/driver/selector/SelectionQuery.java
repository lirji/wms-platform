package com.lrj.wms.driver.selector;

import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.RiskClass;

import java.util.List;

public record SelectionQuery(
        List<DriverAction> requiredActions,
        RiskClass riskClass,
        String preferredDriver,
        String executionMode) {

    public SelectionQuery {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        riskClass = riskClass == null ? RiskClass.NORMAL : riskClass;
    }
}
