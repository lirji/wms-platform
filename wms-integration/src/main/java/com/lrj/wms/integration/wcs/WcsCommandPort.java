package com.lrj.wms.integration.wcs;

/** 向设备/WCS 发送已固定身份的命令。实现不得自行换号。 */
public interface WcsCommandPort {
    WcsDispatchResult dispatch(WcsCommand command);
}
