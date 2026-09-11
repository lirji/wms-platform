package com.lrj.wms.outbound.order;

import java.math.BigDecimal;
import java.util.Map;

/** 向 inventory 申请 STARTED。出库不写库存表。 */
public interface ExecutionAuthorizationPort {
    Map<String, Object> startPermit(String enterpriseId, String warehouseId, String commandId, String taskId,
            long taskEpoch, String parentId, String partId, String lineId, BigDecimal qty);

    Map<String, Object> markUnknown(String enterpriseId, String warehouseId, String commandId);
}
