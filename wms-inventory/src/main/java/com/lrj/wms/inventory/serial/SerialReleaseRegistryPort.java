package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 只传播已持久化的源仓释放事实，返回历史释放凭证，不授予当前库存权限。 */
@FunctionalInterface
public interface SerialReleaseRegistryPort {
    /** 公开调拨先保存本地封闭，随后以原意图准备登记；旧端口不得把未实现准备当作成功。 */
    default Map<String,Object> prepare(String enterprise,String sku,String serial,String transfer,String sourceWarehouse,
            String targetWarehouse,String operation,long fromEpoch) {
        throw new SerialRegistryUnavailableException("未配置序列调拨准备端口");
    }
    Map<String,Object> release(String enterprise,String sku,String serial,String transfer,String sourceWarehouse,String releaseRef,long fromEpoch);
}
