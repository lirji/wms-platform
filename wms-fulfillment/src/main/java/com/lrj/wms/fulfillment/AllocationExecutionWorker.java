package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import static com.lrj.wms.fulfillment.AllocationExecutionService.*;

/** 持久化单步执行器；每轮每企业最多一个外部动作，数据库事务只覆盖领取和条件回写。 */
public final class AllocationExecutionWorker {
    private final SqlSessionFactory sessions;
    private final AllocationTmPort tm;
    private final WarehouseTryPort warehouse;
    private final TcStatusPort audit;
    private final TcEvidenceScope scope;
    private final Clock clock;
    private final com.lrj.wms.runtime.web.AdmissionGate gate=new com.lrj.wms.runtime.web.AdmissionGate(
            new com.lrj.wms.runtime.web.AdmissionBudget(4,1,16,4));
    public AllocationExecutionWorker(SqlSessionFactory sessions,AllocationTmPort tm,WarehouseTryPort warehouse,
            TcStatusPort audit,TcEvidenceScope scope,Clock clock) {
        this.sessions=sessions;this.tm=tm;this.warehouse=warehouse;this.audit=audit;this.scope=scope;this.clock=clock;
    }

    /** 租约过期只能接管原进度；BEGIN_CALLING重启不能再begin，失败项延后以免阻塞其他订单。 */
    public boolean executeOne(String enterprise) {
        if(enterprise==null||enterprise.isBlank()||enterprise.length()>64) throw error("INVALID_ENTERPRISE");
        try(var permit=gate.acquire(enterprise)) {
            if(permit==null) return false;
            Map<String,Object> row;
            boolean begin=false;
            try(var session=sessions.openSession(false)) {
                var mapper=session.getMapper(AllocationExecutionMapper.class);row=mapper.due(enterprise,now());
                if(row==null) {session.commit();return false;}
                long epoch=number(row,"claim_epoch");
                if(mapper.claim(enterprise,text(row,"attempt_id"),epoch,at(clock.instant().plusSeconds(60)),now())!=1) return false;
                row.put("claim_epoch",epoch+1);row.put("lease_until",at(clock.instant().plusSeconds(60)));
                if(!scope.clusterId().equals(row.get("cluster_id"))||!scope.transactionGroup().equals(row.get("transaction_group"))) {
                    row.put("state","ISOLATED");row.put("error_code","TC_BINDING_CONFLICT");release(row,60);save(session,row);session.commit();return true;
                }
                var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(enterprise,text(row,"attempt_id"));
                if("READY".equals(row.get("state"))) {
                    if(cancelled(attempt)||!instant(attempt.get("deadline")).isAfter(clock.instant().plusSeconds(1))) {
                        if(session.getMapper(FulfillmentMapper.class).casAttemptState(enterprise,text(row,"attempt_id"),"CANCELLED","PLANNED",now())!=1)
                            throw error("ATTEMPT_ALREADY_STARTED");
                        row.put("state","ROLLED_BACK");row.put("error_code","CANCELLED_BEFORE_BEGIN");release(row,0);save(session,row);session.commit();return true;
                    }
                    new FulfillmentService(session,clock).claimLaunch(enterprise,text(row,"attempt_id"),text(row,"launch_owner"));
                    row.put("state","BEGIN_CALLING");begin=true;
                } else if("BEGIN_CALLING".equals(row.get("state"))) {
                    new FulfillmentService(session,clock).markLaunchUnknown(enterprise,text(row,"attempt_id"));
                    row.put("state","BEGIN_UNKNOWN");row.put("error_code","TC_BEGIN_UNKNOWN");release(row,0);
                }
                save(session,row);session.commit();
            }
            try {
                if(begin) begin(row);
                else if("TRYING".equals(row.get("state"))) tryWarehouse(row);
                else if(Set.of("FINISH_REQUESTED","WAITING_TERMINAL").contains(text(row,"state"))) finish(row);
            } catch(RuntimeException failure) {
                // 不能靠异常说明业务已回滚；只保存脱敏恢复原因，原动作和XID保持不变。
                recordFailure(row,failure instanceof FulfillmentException known?known.code():"EXECUTION_STEP_UNKNOWN");
            }
            return true;
        }
    }

    private void begin(Map<String,Object> row) {
        int timeout;
        try(var session=sessions.openSession()) {
            var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(text(row,"enterprise_id"),text(row,"attempt_id"));
            timeout=(int)Math.min(60000,Duration.between(clock.instant(),instant(attempt.get("deadline"))).toMillis());
        }
        String xid;
        try {
            if(timeout<1000) throw error("TC_BEGIN_DEADLINE_EXPIRED");
            xid=tm.begin(text(row,"attempt_id"),timeout);
        } catch(RuntimeException failure) {
            try(var session=sessions.openSession(false)) {
                var current=owned(session,row);if(current==null) return;
                new FulfillmentService(session,clock).markLaunchUnknown(text(row,"enterprise_id"),text(row,"attempt_id"));
                current.put("state","BEGIN_UNKNOWN");current.put("error_code","TC_BEGIN_UNKNOWN");release(current,0);save(session,current);session.commit();
            }
            return;
        }
        try(var session=sessions.openSession(false)) {
            var current=owned(session,row);if(current==null) throw error("EXECUTION_LEASE_LOST");
            new FulfillmentService(session,clock).bindXid(text(row,"enterprise_id"),text(row,"attempt_id"),text(row,"launch_owner"),xid,scope);
            current.put("xid",xid);current.put("state","TRYING");current.put("error_code",null);release(current,0);save(session,current);session.commit();
        } catch(RuntimeException unknownBinding) {
            // 原begin回执已知但绑定事务结果不明：先读回；只有确实未绑定且未Try的XID才能登记为空并请求回滚。
            try(var session=sessions.openSession(false)) {
                var current=session.getMapper(AllocationExecutionMapper.class).lock(text(row,"enterprise_id"),text(row,"attempt_id"));
                if(xid.equals(current.get("xid"))) {session.commit();return;}
                if(current.get("xid")!=null||!row.get("launch_owner").equals(current.get("launch_owner"))) throw error("XID_CONFLICT");
                new FulfillmentService(session,clock).recordKnownEmptyXid(text(row,"enterprise_id"),text(row,"attempt_id"),xid);
                current.put("xid",xid);current.put("empty_xid",true);requestFinish(current,"ROLLBACK");
                current.put("error_code","XID_BIND_FAILED");release(current,0);save(session,current);session.commit();
            }
        }
    }

    private void tryWarehouse(Map<String,Object> row) {
        if(observeTerminal(row)) return;
        Map<String,Object> current;
        try(var session=sessions.openSession(false)) {
            current=owned(session,row);if(current==null) return;
            var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(text(row,"enterprise_id"),text(row,"attempt_id"));
            if(cancelled(attempt)||!instant(attempt.get("deadline")).isAfter(clock.instant())||number(current,"rpc_attempts")>=8) {
                requestFinish(current,"ROLLBACK");release(current,0);save(session,current);session.commit();return;
            }
            current.put("rpc_attempts",number(current,"rpc_attempts")+1);save(session,current);session.commit();
        }
        var fixed=requests(current);int index=(int)number(current,"next_warehouse");
        var request=fixed.get(index);
        var result=warehouse.reserve(text(current,"xid"),request);
        if(!request.attemptId().equals(result.attemptId())||!request.allocationId().equals(result.allocationId())
                ||!text(current,"xid").equals(result.xid())||request.routeEpoch()!=result.routeEpoch()
                ||!Set.of("TRIED","CONFIRMED").contains(result.state())) throw error("TRY_RECEIPT_MISMATCH");
        try(var session=sessions.openSession(false)) {
            var owned=owned(session,current);if(owned==null) return;
            new FulfillmentService(session,clock).bindParticipant(request.enterpriseId(),request.attemptId(),request.warehouseId(),
                    result.xid(),result.branchId(),result.actionName(),result.reservationId(),result.routeEpoch(),"TRIED");
            owned.put("next_warehouse",index+1);owned.put("rpc_attempts",0);owned.put("error_code",null);
            if(index+1==fixed.size()) {
                var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(request.enterpriseId(),request.attemptId());
                requestFinish(owned,cancelled(attempt)||!instant(attempt.get("deadline")).isAfter(clock.instant())?"ROLLBACK":"COMMIT");
            }
            release(owned,0);save(session,owned);session.commit();
        }
    }

    private void finish(Map<String,Object> row) {
        if(observeTerminal(row)) return;
        boolean send;
        try(var session=sessions.openSession(false)) {
            var current=owned(session,row);if(current==null) return;
            send=number(current,"rpc_attempts")<8;
            if(send) current.put("rpc_attempts",number(current,"rpc_attempts")+1);
            current.put("state","WAITING_TERMINAL");save(session,current);session.commit();
        }
        if(send) {
            if("COMMIT".equals(row.get("requested_action"))) tm.commit(text(row,"xid"));
            else if("ROLLBACK".equals(row.get("requested_action"))) tm.rollback(text(row,"xid"));
            else throw error("EXECUTION_ACTION_MISSING");
        }
        // SDK返回不授予ALLOCATED；下一轮只读原TC证据后再复用业务屏障。
        reschedule(row,send?2:60,null);
    }

    private boolean observeTerminal(Map<String,Object> row) {
        var observed=audit.read(text(row,"xid"));if(observed.isEmpty()) return false;
        var proof=observed.get();var evidence=RuntimeMessage.JSON.readTree(proof.evidence());
        int code=switch(proof.status()) {case "Committed"->9;case "Rollbacked"->11;case "TimeoutRollbacked"->13;default->-1;};
        if(code<0||!text(row,"xid").equals(evidence.path("xid").asString())||!evidence.path("status").isIntegralNumber()
                ||!evidence.path("status").canConvertToInt()||evidence.path("status").asInt()!=code||!scope.clusterId().equals(evidence.path("clusterId").asString())
                ||!scope.applicationId().equals(evidence.path("applicationId").asString())
                ||!scope.transactionGroup().equals(evidence.path("transactionGroup").asString())) throw error("TC_EVIDENCE_IDENTITY_MISMATCH");
        try(var session=sessions.openSession(false)) {
            var current=owned(session,row);if(current==null) return true;
            boolean empty=Boolean.TRUE.equals(current.get("empty_xid"))||current.get("empty_xid") instanceof Number n&&n.intValue()!=0;
            var service=new FulfillmentService(session,clock);
            current.put("terminal_evidence",proof.evidence());
            if(empty) {
                if(code==9) {current.put("state","ISOLATED");current.put("error_code","EMPTY_XID_COMMITTED");}
                else {
                    service.cleanupEmptyLaunch(text(row,"enterprise_id"),text(row,"attempt_id"),proof,scope);
                    var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(text(row,"enterprise_id"),text(row,"attempt_id"));
                    if(attempt.get("xid")!=null||!current.get("launch_owner").equals(attempt.get("launch_owner"))) throw error("XID_CONFLICT");
                    if(!"CANCELLED".equals(attempt.get("state"))&&session.getMapper(FulfillmentMapper.class).casAttemptState(
                            text(row,"enterprise_id"),text(row,"attempt_id"),"CANCELLED","TCC_STARTING",now())!=1) throw error("VERSION_CONFLICT");
                    current.put("state","ROLLED_BACK");current.put("error_code",null);
                }
            } else {
                service.observeTc(text(row,"enterprise_id"),text(row,"attempt_id"),proof.status(),proof.evidence());
                if(code==9) {
                    if(!"COMMIT".equals(current.get("requested_action"))) {
                        current.put("state","ISOLATED");current.put("error_code","UNEXPECTED_TC_COMMIT");
                    } else try {
                        service.markAllocated(text(row,"enterprise_id"),text(row,"attempt_id"));
                        current.put("state","COMPLETED");current.put("error_code",null);
                    } catch(FulfillmentException pending) {
                        if(!FulfillmentService.isRecoveryPending(pending)&&!"CANCEL_REQUIRES_COMPENSATION".equals(pending.code())) throw pending;
                        current.put("state","WAITING_TERMINAL");current.put("error_code",pending.code());
                    }
                } else {
                    var attempt=session.getMapper(FulfillmentMapper.class).lockAttempt(text(row,"enterprise_id"),text(row,"attempt_id"));
                    if(!Set.of("CANCELLED","FAILED").contains(text(attempt,"state"))
                            &&session.getMapper(FulfillmentMapper.class).casAttemptState(text(row,"enterprise_id"),text(row,"attempt_id"),"CANCELLED",text(attempt,"state"),now())!=1)
                        throw error("VERSION_CONFLICT");
                    current.put("state","ROLLED_BACK");current.put("error_code",null);
                }
            }
            release(current,"WAITING_TERMINAL".equals(current.get("state"))?5:0);save(session,current);session.commit();return true;
        }
    }

    private void recordFailure(Map<String,Object> row,String code) {
        reschedule(row,-1,code);
    }
    private void reschedule(Map<String,Object> row,long seconds,String error) {
        try(var session=sessions.openSession(false)) {
            var current=owned(session,row);if(current==null) return;
            current.put("error_code",error);
            release(current,seconds<0?Math.min(60,1L<<Math.min(6,number(current,"rpc_attempts"))):seconds);
            save(session,current);session.commit();
        }
    }
    private Map<String,Object> owned(SqlSession session,Map<String,Object> row) {
        var current=session.getMapper(AllocationExecutionMapper.class).lock(text(row,"enterprise_id"),text(row,"attempt_id"));
        if(current==null||number(current,"claim_epoch")!=number(row,"claim_epoch")) return null;
        return current;
    }
    private void save(SqlSession session,Map<String,Object> row) {
        if(session.getMapper(AllocationExecutionMapper.class).save(row,number(row,"claim_epoch"),now())!=1) throw error("EXECUTION_LEASE_LOST");
    }
    private void requestFinish(Map<String,Object> row,String action) {
        if(row.get("requested_action")!=null&&!action.equals(row.get("requested_action"))) throw error("EXECUTION_ACTION_IMMUTABLE");
        row.put("requested_action",action);row.put("state","FINISH_REQUESTED");row.put("rpc_attempts",0);
    }
    private void release(Map<String,Object> row,long seconds) {
        row.put("lease_until",null);row.put("next_at",at(clock.instant().plusMillis(seconds*1000+(seconds==0?0:java.util.concurrent.ThreadLocalRandom.current().nextInt(250)))));
    }
    private Timestamp now() {return at(clock.instant());}
    private static Timestamp at(Instant instant) {return Timestamp.from(instant);}
    private static String text(Map<String,Object> row,String field) {return String.valueOf(row.get(field));}
    private static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
}
