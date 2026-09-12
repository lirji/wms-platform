package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 盘点 FOUND/MISSING 调用的登记端口。 */
public interface SerialCountRegistryPort {
    Map<String, Object> markMissing(String enterpriseId, String skuId, String serial, String warehouseId, String factRef,
            long expectedEpoch);

    Map<String, Object> claimFound(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId);

    Map<String, Object> activateFound(String enterpriseId, String skuId, String serial, String warehouseId,
            String operationId);

    Map<String, Object> get(String enterpriseId, String skuId, String serial);
}
