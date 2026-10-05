package com.lrj.wms.fulfillment.domain;

/** 履约映射冲突，携带稳定错误码。 */
public final class FulfillmentException extends RuntimeException {
    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public FulfillmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** HTTP、配置与执行用例共用同一失败语义，外部适配器无需依赖应用服务的包内助手。 */
    public static FulfillmentException executionCondition(String code) {
        return new FulfillmentException(code, "分配执行条件不满足，保留原命令与恢复记录");
    }

    /** 稳定错误码，供调用方映射恢复路径。 */
    public String code() {
        return code;
    }
}
