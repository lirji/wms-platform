package com.lrj.wms.inventory.serial.registry.port;

import java.util.Map;

/** 盘点 FOUND/MISSING 调用的登记端口。 */
public interface SerialCountRegistryPort {
    /** 将缺失观察交给序列号权威处理，调用方不能自行释放全局身份。 */
    Map<String, Object> markMissing(
            String enterpriseId,
            String skuId,
            String serial,
            String warehouseId,
            String factRef,
            long expectedEpoch);

    /** 按全局身份声明发现观察，不能把仓内观察直接当作注册授权。 */
    Map<String, Object> claimFound(
            String enterpriseId,
            String skuId,
            String serial,
            String warehouseId,
            String operationId);

    /** 按已声明的发现操作激活身份，重试不能引入不同登记结果。 */
    Map<String, Object> activateFound(
            String enterpriseId,
            String skuId,
            String serial,
            String warehouseId,
            String operationId);

    /** 按调用方提供的作用域读取既有事实，缺失结果沿用当前用例的处理契约。 */
    Map<String, Object> get(String enterpriseId, String skuId, String serial);
}
