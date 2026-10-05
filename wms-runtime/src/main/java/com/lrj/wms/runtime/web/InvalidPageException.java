package com.lrj.wms.runtime.web;

/** 与资源不存在或权限不足区分，始终转换为分页请求错误。 */
public final class InvalidPageException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public InvalidPageException(String message) {
        super(message);
    }
}
