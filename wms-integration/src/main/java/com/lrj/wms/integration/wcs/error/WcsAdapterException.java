package com.lrj.wms.integration.wcs.error;

/** WCS 适配冲突，携带稳定错误码。 */
public final class WcsAdapterException extends RuntimeException {
    /** 回执构造与状态校验共用同一协议错误身份，不能因入口不同改写错误码。 */
    public static final String INVALID_RECEIPT = "INVALID_RECEIPT";

    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public WcsAdapterException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回稳定协议码，持久化与外部响应不能依赖枚举序号或异常文本。 */
    public String code() {
        return code;
    }
}
