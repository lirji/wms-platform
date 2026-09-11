package com.lrj.wms.integration.wcs;

/** 接收设备回执。旧 worker 可信回执按原命令身份恢复，不得改派新命令。 */
public interface WcsReceiptPort {
    WcsReceiptResult accept(WcsReceipt receipt);
}
