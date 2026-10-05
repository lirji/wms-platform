package com.lrj.wms.inventory.serial.registry.error;

/** 登记业务冲突。库存必须保留本地意向，不得假装认领成功。 */
public final class SerialRegistryConflictException extends RuntimeException {
    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public SerialRegistryConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回稳定协议码，持久化与外部响应不能依赖枚举序号或异常文本。 */
    public String code() {
        return code;
    }
}
