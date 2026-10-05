package com.lrj.wms.inventory.serial.registry.protocol;

import com.lrj.wms.inventory.serial.registry.error.SerialRegistryUnavailableException;

import java.util.Map;

/** 历史登记凭证的共享协议规则；HTTP 适配与本地恢复使用同一验证，不依赖彼此实现。 */
public final class SerialRegistryProof {
    private SerialRegistryProof() {}

    /** 历史释放凭证只证明该事实登记成功；不把响应中的当前身份状态当作任何仓的可用授权。 */
    public static void requireRelease(
            Map<String, Object> response,
            String e,
            String w,
            String sku,
            String serial,
            String transfer,
            String ref,
            long epoch) {
        if (!(response.get("sourceRelease") instanceof Map<?, ?> proof)
                || !e.equals(proof.get("enterpriseId"))
                || !w.equals(proof.get("sourceWarehouseId"))
                || !sku.equals(proof.get("skuId"))
                || !serial.equals(proof.get("normalizedSerial"))
                || !transfer.equals(proof.get("transferId"))
                || !ref.equals(proof.get("sourceReleaseRef"))
                || !(proof.get("fromEpoch") instanceof Number number)
                || !(number instanceof Long || number instanceof Integer)
                || number.longValue() != epoch)
            throw new SerialRegistryUnavailableException("原始源仓释放凭证不完整或不匹配");
    }

    /** 只接受完整原事实证明；当前身份可能进入后续生命周期，不能用它重建历史发运。 */
    public static void requireShipment(
            Map<String, Object> result,
            String e,
            String w,
            String sku,
            String serial,
            String ref,
            long epoch) {
        if (!(result.get("shipment") instanceof Map<?, ?> proof)
                || !integerEquals(proof.get("schemaVersion"), 1)
                || !e.equals(proof.get("enterpriseId"))
                || !w.equals(proof.get("warehouseId"))
                || !sku.equals(proof.get("skuId"))
                || !serial.equals(proof.get("normalizedSerial"))
                || !ref.equals(proof.get("shipmentRef"))
                || !integerEquals(proof.get("ownerEpoch"), epoch))
            throw new SerialRegistryUnavailableException("原发运证明不完整或不匹配");
    }

    private static boolean integerEquals(Object value, long expected) {
        return (value instanceof Long || value instanceof Integer)
                && ((Number) value).longValue() == expected;
    }
}
