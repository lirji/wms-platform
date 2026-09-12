package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 库存目的仓接收时调用的登记转移端口。失败必须可区分不可用与业务冲突。 */
public interface SerialTransferRegistryPort {
    Map<String, Object> startReceiving(String enterpriseId, String skuId, String serial, String transferId,
            String targetWarehouseId, String targetReceiptRef, long expectedFromEpoch);

    Map<String, Object> confirmDestination(String enterpriseId, String skuId, String serial, String transferId,
            String targetWarehouseId, String targetReceiptRef);
}
