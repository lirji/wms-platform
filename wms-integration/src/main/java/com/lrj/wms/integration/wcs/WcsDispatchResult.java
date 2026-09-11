package com.lrj.wms.integration.wcs;

/** 派发受理结果。ACCEPTED 不是设备已完成。 */
public record WcsDispatchResult(String deviceCommandId, String state, boolean replayed) {
}
