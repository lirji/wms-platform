package com.lrj.wms.fulfillment;

import java.util.Optional;

/** 只读 TC 终态观察。缺实现或查询失败必须为空，不得发明放行。 */
public interface TcStatusPort {
    Optional<Observation> read(String xid);

    record Observation(String status, String evidence) {
    }
}
