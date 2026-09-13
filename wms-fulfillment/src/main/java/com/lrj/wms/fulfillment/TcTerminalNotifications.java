package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.messaging.TcTerminalNotice;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 观察与逐仓Outbox同事务；固定原attempt，不因重启或消息重排重建XID。 */
final class TcTerminalNotifications {
    private TcTerminalNotifications() {}
    static void enqueue(SqlSession session,Clock clock,String enterprise,Map<String,Object> attempt,
            TcEvidenceScope scope,TcStatusPort.Observation observation) {
        var evidence=RuntimeMessage.JSON.readTree(observation.evidence());
        if(!scope.clusterId().equals(evidence.path("clusterId").asString())
                || !scope.applicationId().equals(evidence.path("applicationId").asString())
                || !scope.transactionGroup().equals(evidence.path("transactionGroup").asString()))
            throw new FulfillmentException("TC_EVIDENCE_IDENTITY_MISMATCH","通知必须来自绑定的只读TC审计");
        String attemptId=(String)attempt.get("id");
        var notice=new TcTerminalNotice(1,attemptId,attemptId,(String)attempt.get("xid"),
                scope.clusterId(),scope.applicationId(),scope.transactionGroup(),evidence.path("status").intValue());
        var mapper=session.getMapper(FulfillmentMapper.class);
        for(var participant:mapper.lockParticipants(enterprise,attemptId)) {
            String warehouse=(String)participant.get("warehouse_id");
            String id=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(java.util.List.of(TcTerminalNotice.EVENT,enterprise,attemptId,warehouse)));
            mapper.insertOutboxIgnore(id,enterprise,attemptId,warehouse,TcTerminalNotice.EVENT,id,
                    RuntimeMessage.JSON.writeValueAsString(notice),Timestamp.from(clock.instant()));
        }
    }
}
