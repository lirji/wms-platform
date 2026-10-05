package com.lrj.wms.security.authorization;

/** 令牌仓范围不包含目标仓。 */
public final class WarehouseForbiddenException extends RuntimeException {
    public WarehouseForbiddenException(String warehouseId) {
        super("WAREHOUSE_FORBIDDEN:" + warehouseId);
    }
}
