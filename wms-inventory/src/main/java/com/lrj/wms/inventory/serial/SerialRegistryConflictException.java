package com.lrj.wms.inventory.serial;

/** 登记业务冲突。库存必须保留本地意向，不得假装认领成功。 */
public final class SerialRegistryConflictException extends RuntimeException {
    private final String code;

    public SerialRegistryConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
