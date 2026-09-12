package com.lrj.wms.fulfillment;

import java.util.Optional;

/** 只读 TC 终态观察。缺行返回空；审计不可用可显式失败，任何情况不得发明放行。 */
public interface TcStatusPort {
    Optional<Observation> read(String xid);

    record Observation(String status, String evidence) {
    }
}
