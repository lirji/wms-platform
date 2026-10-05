package com.lrj.wms.fulfillment.tc.port;

import java.util.Optional;

/** 只读 TC 终态观察。缺行返回空；审计不可用可显式失败，任何情况不得发明放行。 */
public interface TcStatusPort {
    /** 从权威观察入口读取既有事实，无法确定时沿用缺失观察语义。 */
    Optional<Observation> read(String xid);

    record Observation(String status, String evidence) {}
}
