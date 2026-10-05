package com.lrj.wms.inventory.serial.registry.port;

import java.util.Map;

/** 只传播已提交的原发运事实；确认不能替代库存扣减或授予新归属。 */
@FunctionalInterface
public interface SerialShipmentRegistryPort {
    /** 沿用序列号归属和发运操作身份登记发运，重复请求不能产生另一归属效果。 */
    Map<String, Object> ship(
            String enterprise,
            String sku,
            String serial,
            String warehouse,
            String shipmentRef,
            long ownerEpoch);
}
