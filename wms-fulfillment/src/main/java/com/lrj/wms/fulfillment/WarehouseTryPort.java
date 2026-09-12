package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.contract.tcc.WarehouseTryResult;

/** 显式的原XID/冻结桶Try网关；不存在业务Confirm/Cancel HTTP。 */
public interface WarehouseTryPort {
    /** 重放必须保持原始请求，响应身份由适配层完整校验。 */
    WarehouseTryResult reserve(String xid, WarehouseTryRequest request);
}
