package com.lrj.wms.integration.wcs.port;

import com.lrj.wms.integration.wcs.model.WcsCommandView;

import java.util.Optional;

/** 查询已派发命令。空结果表示未知，不能当成「可重新派发」。 */
public interface WcsQueryPort {
    /** 按企业、仓与命令身份读取观察，缺失结果不能推断为设备成功。 */
    Optional<WcsCommandView> query(String enterpriseId, String warehouseId, String deviceCommandId);
}
