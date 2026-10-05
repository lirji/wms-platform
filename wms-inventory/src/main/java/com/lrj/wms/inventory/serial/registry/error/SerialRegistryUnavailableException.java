package com.lrj.wms.inventory.serial.registry.error;

/** 登记服务不可达或超时。调用方必须保留本地意向。 */
public final class SerialRegistryUnavailableException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public SerialRegistryUnavailableException(String message) {
        super(message);
    }
}
