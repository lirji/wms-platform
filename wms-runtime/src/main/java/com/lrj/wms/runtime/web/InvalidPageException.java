package com.lrj.wms.runtime.web;

/** 与资源不存在或权限不足区分，始终转换为分页请求错误。 */
public final class InvalidPageException extends RuntimeException {
    public InvalidPageException(String message) {
        super(message);
    }
}
