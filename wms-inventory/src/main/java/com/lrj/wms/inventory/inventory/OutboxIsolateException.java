package com.lrj.wms.inventory.inventory;

/** 毒消息：停止自动重试，写入 ISOLATED，不得绕过继续投递后续依赖事件。 */
public final class OutboxIsolateException extends RuntimeException {
    public OutboxIsolateException(String message) {
        super(message);
    }
}
