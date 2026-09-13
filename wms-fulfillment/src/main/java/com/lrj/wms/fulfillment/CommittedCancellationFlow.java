package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 原取消和已提交证据都存在才追加逐仓补偿；确定性身份让扫描和HTTP竞争也只有一次意图。 */
final class CommittedCancellationFlow {
    private CommittedCancellationFlow() {}
    static void enqueue(SqlSession session,Clock clock,String e,Map<String,Object> attempt) {
        if(attempt==null || !FulfillmentService.cancelRequested(attempt.get("cancel_requested"))
                || !"Committed".equals(attempt.get("tc_observed_status"))
                || attempt.get("tc_terminal_evidence")==null) return;
        String a=(String)attempt.get("id");
        var source=session.getMapper(FulfillmentCancelMapper.class).compensationSource(e,a);
        if(source==null) return; // 历史计划缺失必须等待人工核实，不能猜测原库存桶。
        var proof=RuntimeMessage.JSON.readTree(attempt.get("tc_terminal_evidence").toString());
        var terminal=new TcTerminalNotice(1,a,a,(String)attempt.get("xid"),proof.path("clusterId").asString(),
                proof.path("applicationId").asString(),proof.path("transactionGroup").asString(),proof.path("status").intValue());
        session.getMapper(FulfillmentCancelMapper.class).bindCanonical(e,a,(String)source.get("id"));
        session.getMapper(FulfillmentCancelMapper.class).startCompensation(e,a);
        session.getMapper(FulfillmentCancelMapper.class).finishCompensation(e,a);
        for(var request:AllocationExecutionService.requests(source)) {
            var value=new CommittedCancellation(1,(String)source.get("id"),(String)source.get("actor_id"),request,terminal);
            String id=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(java.util.List.of(CommittedCancellation.EVENT,e,a,request.warehouseId())));
            session.getMapper(FulfillmentMapper.class).insertOutboxIgnore(id,e,a,request.warehouseId(),CommittedCancellation.EVENT,id,
                    RuntimeMessage.JSON.writeValueAsString(value),Timestamp.from(clock.instant()));
        }
    }
    /** 仅可信出库回执可推进逐仓结果，检查原已发送决定，不能由客户端自报完成。 */
    static void complete(SqlSession session,RuntimeMessage message) {
        var body=message.payload();String e=message.enterpriseId(),a=message.aggregateId(),w=message.warehouseId();
        if(!"wms-outbound".equals(message.sourceService()) || !CommittedCancellation.RESULT.equals(message.eventType())
                || message.aggregateVersion()!=1 || !body.path("schemaVersion").isIntegralNumber() || body.path("schemaVersion").asInt()!=1
                || !a.equals(body.path("attemptId").asString()) || !java.util.Set.of("COMPLETED","PARTIALLY_COMPENSATED").contains(body.path("state").asString()))
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("INVALID_CANCELLATION_RESULT");
        var mapper=session.getMapper(FulfillmentMapper.class);
        if(mapper.lockAttempt(e,a)==null) throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CANCELLATION_ATTEMPT_MISSING");
        var sent=mapper.getBarrierOutbox(e,a,w,CommittedCancellation.EVENT);
        if(sent==null) throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CANCELLATION_ORIGINAL_MISSING");
        var original=RuntimeMessage.JSON.readValue(sent.get("payload").toString(),CommittedCancellation.class);
        if(!original.cancellationId().equals(body.path("cancellationId").asString()))
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CANCELLATION_RESULT_MISMATCH");
        try {
            var released=new java.math.BigDecimal(body.path("releasedQty").asString());
            var executed=new java.math.BigDecimal(body.path("executedQty").asString());
            var total=original.request().lines().stream().map(com.lrj.wms.contract.tcc.WarehouseTryRequest.Line::qty).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
            if(released.signum()<0 || executed.signum()<0 || released.add(executed).compareTo(total)!=0
                    || ("COMPLETED".equals(body.path("state").asString())!=(executed.signum()==0))) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CANCELLATION_RESULT_QUANTITY_MISMATCH");}
        String payload=body.toString(),hash=RuntimeMessage.contentHash(payload);
        var cancels=session.getMapper(FulfillmentCancelMapper.class);
        cancels.insertCompensationResult(Map.of("e",e,"a",a,"w",w,"id",original.cancellationId(),"state",body.path("state").asString(),"payload",payload,"hash",hash));
        if(!hash.equals(cancels.compensationResult(e,a,w).get("payload_hash")))
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CANCELLATION_RESULT_CONFLICT");
        cancels.finishCompensation(e,a);
        cancels.finishExecution(e,a);
    }
}
