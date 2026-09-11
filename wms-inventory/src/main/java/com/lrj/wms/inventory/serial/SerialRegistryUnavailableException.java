package com.lrj.wms.inventory.serial;

/** 登记服务不可达或超时。调用方必须保留本地意向。 */
public final class SerialRegistryUnavailableException extends RuntimeException {
    public SerialRegistryUnavailableException(String message) {
        super(message);
    }
}
