package com.lrj.wms.outbound.authorization.port;

import java.math.BigDecimal;
import java.util.Map;

/** 向 inventory 申请 STARTED。出库不写库存表。 */
public interface ExecutionAuthorizationPort {
    /** 在首次物理动作前核对既有执行授权，未知或失效授权不能默认执行。 */
    Map<String, Object> startPermit(
            String enterpriseId,
            String warehouseId,
            String commandId,
            String taskId,
            long taskEpoch,
            String parentId,
            String partId,
            String lineId,
            BigDecimal qty);

    /** 将结果不可确认的命令记录为未知，不能超时后推断动作未发生。 */
    Map<String, Object> markUnknown(String enterpriseId, String warehouseId, String commandId);
}
