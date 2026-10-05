package com.lrj.wms.inventory.serial.registry.port;

import java.util.Map;

/** 库存调用全局登记的端口。实现失败必须可区分不可用与业务冲突。 */
public interface SerialRegistryPort {
    /** 领取由应用入口检查状态与版本，竞争者不能把领取失败当作成功。 */
    Map<String, Object> claim(
            String enterpriseId,
            String skuId,
            String serial,
            String warehouseId,
            String operationId);

    /** 沿用已声明的序列号和操作身份激活，不能重新分配全局物品身份。 */
    Map<String, Object> activate(
            String enterpriseId,
            String skuId,
            String serial,
            String warehouseId,
            String operationId);

    /** 按调用方提供的作用域读取既有事实，缺失结果沿用当前用例的处理契约。 */
    Map<String, Object> get(String enterpriseId, String skuId, String serial);
}
