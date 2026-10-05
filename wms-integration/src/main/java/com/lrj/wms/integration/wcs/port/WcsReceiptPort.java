package com.lrj.wms.integration.wcs.port;

import com.lrj.wms.integration.wcs.model.WcsReceipt;
import com.lrj.wms.integration.wcs.model.WcsReceiptResult;

/** 接收设备回执。旧 worker 可信回执按原命令身份恢复，不得改派新命令。 */
public interface WcsReceiptPort {
    /** 回执沿用既定事件身份与内容冲突规则，观察不能替代业务库存授权。 */
    WcsReceiptResult accept(WcsReceipt receipt);
}
