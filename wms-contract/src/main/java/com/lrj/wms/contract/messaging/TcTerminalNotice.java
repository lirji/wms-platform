package com.lrj.wms.contract.messaging;

/** 只读TC审计的可靠逐仓通知；它是终态证据，不能触发业务Confirm/Cancel。 */
public record TcTerminalNotice(Number schemaVersion,String attemptId,String allocationId,String xid,
        String clusterId,String applicationId,String transactionGroup,Number terminalStatus) {
    public static final String EVENT="TcTerminalNoticeV1";
    public TcTerminalNotice {
        if(!(schemaVersion instanceof Integer || schemaVersion instanceof Long) || schemaVersion.longValue()!=1
                || !(terminalStatus instanceof Integer || terminalStatus instanceof Long)
                || !java.util.Set.of(9L,11L,13L).contains(terminalStatus.longValue())) throw new IllegalArgumentException("无效TC终态通知");
        schemaVersion=1;terminalStatus=terminalStatus.intValue();
        for(String value:java.util.List.of(attemptId,allocationId,clusterId))
            if(value.isBlank() || value.length()>64) throw new IllegalArgumentException("TC通知身份无效");
        if(xid==null || xid.isBlank() || xid.length()>128 || !"wms-fulfillment".equals(applicationId)
                || transactionGroup==null || !transactionGroup.matches("[A-Za-z0-9_.-]{1,32}")) throw new IllegalArgumentException("TC通知来源无效");
    }
}
