package com.lrj.wms.integration.wcs;

/** 回执受理。replayed=true 表示同 eventId 已记录。 */
public record WcsReceiptResult(String deviceCommandId, String eventId, String commandState, boolean replayed) {
}
