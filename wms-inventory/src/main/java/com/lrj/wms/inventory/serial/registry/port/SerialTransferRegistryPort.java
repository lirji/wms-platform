package com.lrj.wms.inventory.serial.registry.port;

import java.util.Map;

/** 库存目的仓接收时调用的登记转移端口。失败必须可区分不可用与业务冲突。 */
public interface SerialTransferRegistryPort {
    /** 登记目标仓开始接收的观察，不能跳过原调拨身份与归属约束。 */
    Map<String, Object> startReceiving(
            String enterpriseId,
            String skuId,
            String serial,
            String transferId,
            String targetWarehouseId,
            String targetReceiptRef,
            long expectedFromEpoch);

    /** 以同一调拨身份确认目标归属，不能用仓内观察替代全局归属校验。 */
    Map<String, Object> confirmDestination(
            String enterpriseId,
            String skuId,
            String serial,
            String transferId,
            String targetWarehouseId,
            String targetReceiptRef);
}
