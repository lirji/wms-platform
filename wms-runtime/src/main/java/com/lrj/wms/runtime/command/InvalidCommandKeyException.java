package com.lrj.wms.runtime.command;

/** 请求头与正文必须表达同一命令，避免同一幂等头被正文换键绕过。 */
public final class InvalidCommandKeyException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public InvalidCommandKeyException() {
        super("clientOperationId必须与Idempotency-Key一致且长度不超过64");
    }
}
