package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 只传播已提交的原发运事实；确认不能替代库存扣减或授予新归属。 */
@FunctionalInterface
public interface SerialShipmentRegistryPort {
    Map<String,Object> ship(String enterprise,String sku,String serial,String warehouse,String shipmentRef,long ownerEpoch);
}
