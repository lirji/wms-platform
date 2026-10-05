package com.lrj.wms.security.authorization;

/** 令牌仓范围不包含目标仓。 */
public final class WarehouseForbiddenException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public WarehouseForbiddenException(String warehouseId) {
        super("WAREHOUSE_FORBIDDEN:" + warehouseId);
    }
}
