package com.lrj.wms.fulfillment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 显式把已绑定 XID 传给下游 Try。空上下文或错 XID 不得发起。
 * 本切片不启动 Seata 全局事务，也不驱动 Confirm/Cancel。
 */
public final class TryPropagation {
    public static final String XID_HEADER = "TX_XID";
    public static final String TM_HEADER = "X-Wms-Tm";
    public static final String TM_IDENTITY = "wms-fulfillment";

    private TryPropagation() {
    }

    /** 仅在 attempt 已绑定 XID 时生成下游请求头。 */
    public static Map<String, String> headers(String xid) {
        if (xid == null || xid.isBlank()) {
            throw new FulfillmentException("XID_NOT_BOUND", "未绑定XID，不能传播空上下文");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(XID_HEADER, xid.trim());
        headers.put(TM_HEADER, TM_IDENTITY);
        return Map.copyOf(headers);
    }

    /** 下游必须拿到与 attempt 相同的 XID 和 TM 身份。 */
    public static void requireMatch(Map<String, String> headers, String boundXid) {
        if (headers == null || boundXid == null || boundXid.isBlank()) {
            throw new FulfillmentException("XID_NOT_BOUND", "缺少已绑定XID，拒绝Try");
        }
        String headerXid = headers.get(XID_HEADER);
        String tm = headers.get(TM_HEADER);
        if (headerXid == null || headerXid.isBlank()) {
            throw new FulfillmentException("XID_NOT_BOUND", "缺少TX_XID，拒绝隐式加入全局事务");
        }
        if (!boundXid.equals(headerXid)) {
            throw new FulfillmentException("XID_MISMATCH", "传播XID必须与attempt绑定值一致");
        }
        if (!TM_IDENTITY.equals(tm)) {
            throw new FulfillmentException("TM_MISMATCH", "只接受TM身份wms-fulfillment");
        }
    }
}
