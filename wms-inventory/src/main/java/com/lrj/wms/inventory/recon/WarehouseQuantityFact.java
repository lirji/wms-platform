package com.lrj.wms.inventory.recon;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WarehouseQuantityFact v1。数量用十进制字符串，禁止把单位塞进 currency。
 */
public final class WarehouseQuantityFact {
    public static final int SCHEMA_VERSION = 1;

    private WarehouseQuantityFact() {
    }

    public static Map<String, Object> onHand(String factId, String enterpriseId, String warehouseId, String ownerId,
            String skuId, String lotId, String serialId, BigDecimal quantity, String unit, String cutoffId,
            String watermark) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("factId", factId);
        fact.put("factVersion", 1);
        fact.put("schemaVersion", SCHEMA_VERSION);
        fact.put("enterpriseId", enterpriseId);
        fact.put("sourceSystem", "wms-inventory");
        fact.put("scenarioCode", "WMS_ONHAND_QTY");
        fact.put("side", "WMS");
        fact.put("warehouseId", warehouseId);
        fact.put("ownerId", ownerId);
        fact.put("skuId", skuId);
        fact.put("businessLotKey", lotId);
        fact.put("serialId", serialId);
        fact.put("factKind", "POSTED");
        fact.put("physicalStatus", "ON_HAND");
        fact.put("stockSyncStatus", "POSTED");
        fact.put("quantity", quantity.stripTrailingZeros().toPlainString());
        fact.put("unit", unit);
        fact.put("cutoffId", cutoffId);
        fact.put("sourceWatermark", watermark);
        return fact;
    }
}
