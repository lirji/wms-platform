package com.lrj.wms.inventory.inventory;

/** 已领取 Outbox 的投递出口。实现不得假装 broker 已配置成功。 */
public interface OutboxTransport {
    /** 至少一次投递；失败抛运行时异常，毒消息抛 {@link OutboxIsolateException}。 */
    void publish(OutboxRecord record);
}
