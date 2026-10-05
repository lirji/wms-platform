package com.lrj.wms.integration.wcs.port;

import com.lrj.wms.integration.wcs.model.WcsCommand;
import com.lrj.wms.integration.wcs.model.WcsDispatchResult;

/** 向设备/WCS 发送已固定身份的命令。实现不得自行换号。 */
public interface WcsCommandPort {
    /** 派发使用固定命令身份，重派不能改变载荷或产生新的物理动作身份。 */
    WcsDispatchResult dispatch(WcsCommand command);
}
