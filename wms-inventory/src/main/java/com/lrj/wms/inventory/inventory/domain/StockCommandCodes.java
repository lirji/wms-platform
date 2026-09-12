package com.lrj.wms.inventory.inventory.domain;

/** 库存命令、凭证与授权的稳定编码。 */
public final class StockCommandCodes {
    public static final String SOURCE_INBOUND = "wms-inbound";
    public static final String SOURCE_OUTBOUND = "wms-outbound";

    public static final String CMD_PENDING = "PENDING";
    public static final String CMD_APPLIED = "APPLIED";
    public static final String CMD_REJECTED = "REJECTED";
    public static final String CMD_CANCELLED = "CANCELLED";

    public static final String PERMIT_PREPARED = "PREPARED";
    public static final String PERMIT_STARTED = "STARTED";
    public static final String PERMIT_UNKNOWN = "UNKNOWN";
    public static final String PERMIT_POSTED = "POSTED";
    public static final String PERMIT_CANCELLED = "CANCELLED";

    public static final String POSTING_RECEIPT = "RECEIPT";
    public static final String POSTING_PICK = "PICK";
    public static final String POSTING_SHIPMENT = "SHIPMENT";
    public static final String POSTING_COMPENSATION = "COMPENSATION";

    public static final String NO_SOURCE_EXECUTION = "NO_SOURCE_EXECUTION";

    private StockCommandCodes() {
    }
}
