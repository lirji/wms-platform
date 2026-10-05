package com.lrj.wms.inventory.inventory.domain;

/** 库存内核冲突，携带稳定错误码。 */
public final class InventoryException extends RuntimeException {
    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public InventoryException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回本值对象绑定的稳定业务编码，调用方据此执行一致的身份或策略判断。 */
    public String code() {
        return code;
    }
}
