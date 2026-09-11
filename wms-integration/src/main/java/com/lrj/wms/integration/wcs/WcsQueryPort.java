package com.lrj.wms.integration.wcs;

import java.util.Optional;

/** 查询已派发命令。空结果表示未知，不能当成「可重新派发」。 */
public interface WcsQueryPort {
    Optional<WcsCommandView> query(String enterpriseId, String warehouseId, String deviceCommandId);
}
