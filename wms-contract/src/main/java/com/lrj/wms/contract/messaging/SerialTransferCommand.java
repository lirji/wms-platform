package com.lrj.wms.contract.messaging;

import java.util.Set;

/** 调拨消息固定原单行、两仓和逐身份epoch；数量由身份集合唯一确定，不接受第二个数量权威。 */
public record SerialTransferCommand(Number schemaVersion,String commandId,String action,String transferId,String lineId,
        String sourceWarehouseId,String targetWarehouseId,String businessLotKey,StockPostingContext postingContext,SerialExecutionSelection selection,String actorId) {
    public static final String EVENT="SerialTransferCommandV1";
    public static final String RESULT="SerialTransferResultV1";
    public SerialTransferCommand {
        if(!(schemaVersion instanceof Integer || schemaVersion instanceof Long) || schemaVersion.longValue()!=1)
            throw new IllegalArgumentException("调拨命令只接受V1");
        schemaVersion=1;
        for(String id:new String[]{commandId,transferId,lineId,sourceWarehouseId,targetWarehouseId,businessLotKey,actorId})
            if(id==null || id.isBlank() || id.length()>64) throw new IllegalArgumentException("调拨原身份缺失或超长");
        if(!Set.of("ISSUE","RECEIVE").contains(action) || sourceWarehouseId.equals(targetWarehouseId) || postingContext==null || selection==null)
            throw new IllegalArgumentException("调拨动作、两仓及身份集合不完整");
        if(!transferId.equals(postingContext.documentId()) || postingContext.targetLocationId()!=null || postingContext.allocationId()!=null || postingContext.allocationAttemptId()!=null
                || "RECEIVE".equals(action) && !"HOLD".equals(postingContext.qualityCode()))
            throw new IllegalArgumentException("调拨使用本仓明确桶，目的先进入HOLD");
        if(selection.identities().stream().anyMatch(i->i.ownerEpoch().longValue()==Long.MAX_VALUE))
            throw new IllegalArgumentException("归属代际已耗尽，禁止溢出授权");
    }
    /** 信封仓必须与实际动作角色相同，不能通过正文改变路由范围。 */
    public String warehouseId() {return "ISSUE".equals(action)?sourceWarehouseId:targetWarehouseId;}
}
