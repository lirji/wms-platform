package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 库存调用全局登记的端口。实现失败必须可区分不可用与业务冲突。 */
public interface SerialRegistryPort {
    Map<String, Object> claim(String enterpriseId, String skuId, String serial, String warehouseId, String operationId);

    Map<String, Object> activate(String enterpriseId, String skuId, String serial, String warehouseId, String operationId);

    Map<String, Object> get(String enterpriseId, String skuId, String serial);
}
