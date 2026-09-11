package com.lrj.wms.fulfillment;

import java.util.Optional;

/** 默认端口：未配置正式只读审计时不合成 TC 终态。 */
public final class UnavailableTcStatusPort implements TcStatusPort {
    @Override
    public Optional<Observation> read(String xid) {
        return Optional.empty();
    }
}
