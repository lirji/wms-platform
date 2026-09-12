package com.lrj.wms.inventory.serial;

import java.util.Map;

/** 只传播已持久化的源仓释放事实，返回历史释放凭证，不授予当前库存权限。 */
@FunctionalInterface
public interface SerialReleaseRegistryPort {
    Map<String,Object> release(String enterprise,String sku,String serial,String transfer,String sourceWarehouse,String releaseRef,long fromEpoch);
}
