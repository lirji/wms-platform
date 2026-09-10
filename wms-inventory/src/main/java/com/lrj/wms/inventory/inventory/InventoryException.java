package com.lrj.wms.inventory.inventory;

/** 库存内核冲突，携带稳定错误码。 */
public final class InventoryException extends RuntimeException {
    private final String code;

    public InventoryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
