package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.serial.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSessionFactory;

/** 逐身份登记在事务外执行；某个身份失败不会撤销其他已保存凭证，也不会提前调整本地数量。 */
public final class CountSerialRecovery {
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final SerialCountRegistryPort registry;
    public CountSerialRecovery(SqlSessionFactory sessions,Clock clock,SerialCountRegistryPort registry) {this.sessions=sessions;this.clock=clock;this.registry=registry;}

    /** 每计划每次至多20行准备、20个身份；20秒后停止新领取，单次HTTP由调用器另设截止。 */
    public Report execute(String e,String w,String plan) {
        int failed=0,waiting=0,completed=0;List<String> lines;
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);
            var original=session.getMapper(CountMapper.class).lockPlan(e,w,plan);
            if(original!=null && "COMPLETED".equals(original.get("status"))) return new Report(0,0,0);
            CountSerialAdjustmentService.requireApproved(original);
            lines=session.getMapper(CountSerialMapper.class).unstagedLines(e,w,plan,Timestamp.from(clock.instant()));session.commit();
        }
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        for(String line:lines) {
            if(System.nanoTime()>=deadline || Thread.currentThread().isInterrupted()) break;
            try(var session=sessions.openSession(false)) {
                new CountSerialAdjustmentService(session,clock).stage(e,w,plan,line,operation(e,w,plan,line),"job:countApplyRecovery");session.commit();
            } catch(RuntimeException failure) {
                String code=failure instanceof InventoryException business?business.code():"COUNT_STAGE_FAILED";
                boolean pending="COUNT_REGISTRY_PENDING".equals(code);
                try(var session=sessions.openSession(false)) {
                    SerialRecoveryService.requireWritable(session,e,w);
                    CountSerialAdjustmentService.requireApproved(session.getMapper(CountMapper.class).lockPlan(e,w,plan));
                    session.getMapper(CountSerialMapper.class).deferStage(e,w,line,pending,code,Timestamp.from(clock.instant().plusSeconds(30)));session.commit();
                }
                if(pending) waiting++;else failed++;
            }
        }
        for(int n=0;n<20 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();n++) {
            Map<String,Object> intent;
            try {intent=claim(e,w,plan);}
            catch(InventoryException missingClient) {if(!"REGISTRY_REQUIRED".equals(missingClient.code())) throw missingClient;failed++;break;}
            if(intent==null) break;
            try {
                String sku=intent.get("sku_id").toString(),serial=intent.get("serial_id").toString(),op=intent.get("operation_id").toString();
                Map<String,Object> result;
                if("MISSING".equals(intent.get("kind"))) result=registry.markMissing(e,sku,serial,w,op,number(intent,"from_epoch"));
                else {registry.claimFound(e,sku,serial,w,op);result=registry.activateFound(e,sku,serial,w,op);}
                requireProof(intent,w,result);
                if(finish(e,w,plan,intent,"DONE",RuntimeMessage.JSON.writeValueAsString(result),null,0)) completed++;
            } catch(RuntimeException failure) {
                String code=failure instanceof SerialRegistryConflictException conflict?conflict.code():"REGISTRY_UNAVAILABLE";
                boolean permanent=failure instanceof SerialRegistryConflictException && !Set.of("SERIAL_STATE_CONFLICT","VERSION_CONFLICT").contains(code);
                long attempts=number(intent,"attempts"),delay=Math.min(300000L,1000L<<Math.min(attempts,8))+ThreadLocalRandom.current().nextLong(501);
                finish(e,w,plan,intent,permanent || attempts>=12?"ISOLATED":"PENDING",null,code,delay);failed++;
            }
        }
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);
            if(!active(session,e,w,plan)) return new Report(completed,failed,waiting);
            session.getMapper(CountSerialMapper.class).ready(e,w,plan,Timestamp.from(clock.instant()));session.commit();
        }
        return new Report(completed,failed,waiting);
    }
    private Map<String,Object> claim(String e,String w,String plan) {
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);if(!active(session,e,w,plan)) return null;
            var mapper=session.getMapper(CountSerialMapper.class);var now=Timestamp.from(clock.instant());var row=mapper.next(e,w,plan,now);
            if(row==null) return null;
            if(registry==null) throw new InventoryException("REGISTRY_REQUIRED","逐身份恢复需要真实登记客户端，未配置时不消耗预算");
            if(number(row,"attempts")>=12) {mapper.finish(e,w,row.get("id").toString(),number(row,"claim_epoch"),"ISOLATED",null,"ATTEMPTS_EXHAUSTED",now,now);session.commit();return null;}
            if(mapper.claim(e,w,row.get("id").toString(),number(row,"claim_epoch"),Timestamp.from(clock.instant().plusSeconds(15)),now)!=1)
                throw new InventoryException("VERSION_CONFLICT","盘点身份恢复领取竞争");
            row.put("claim_epoch",number(row,"claim_epoch")+1);row.put("attempts",number(row,"attempts")+1);session.commit();return row;
        }
    }
    private boolean finish(String e,String w,String plan,Map<String,Object> row,String state,String result,String error,long delay) {
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);if(!active(session,e,w,plan)) return false;
            int changed=session.getMapper(CountSerialMapper.class).finish(e,w,row.get("id").toString(),number(row,"claim_epoch"),state,result,error,
                    Timestamp.from(clock.instant().plusMillis(delay)),Timestamp.from(clock.instant()));session.commit();return changed==1;
        }
    }
    /** 接管者已完成并解冻时，迟到回执只能退出，不能再次推进或报业务失败。 */
    private static boolean active(org.apache.ibatis.session.SqlSession session,String e,String w,String plan) {
        var row=session.getMapper(CountMapper.class).lockPlan(e,w,plan);
        if(row!=null && "COMPLETED".equals(row.get("status"))) return false;
        CountSerialAdjustmentService.requireApproved(row);return true;
    }
    /** 必须是原身份、原仓、原操作的准确终态，不能接受普通ACTIVE或不含原引用的响应。 */
    static void requireProof(Map<String,Object> row,String warehouse,Map<String,Object> proof) {
        boolean missing="MISSING".equals(row.get("kind"));Object raw=proof.get("ownerEpoch");
        if(!(raw instanceof Number epoch) || !(epoch instanceof Long || epoch instanceof Integer) || epoch.longValue()<0
                || !row.get("serial_id").equals(proof.get("normalizedSerial")) || !warehouse.equals(proof.get("ownerWarehouseId"))
                || !row.get("operation_id").equals(proof.get("receiptOperationId")) || !(missing?"MISSING":"ACTIVE").equals(proof.get("state"))
                || missing && epoch.longValue()!=number(row,"from_epoch")
                || !missing && (!row.get("operation_id").equals(proof.get("claimOperationId")) || row.get("from_epoch")!=null && epoch.longValue()<=number(row,"from_epoch")))
            throw new SerialRegistryUnavailableException("盘点登记响应与原身份、操作或代际不匹配");
    }
    static String operation(String e,String w,String plan,String line) {
        return UUID.nameUUIDFromBytes((e+"\u0000"+w+"\u0000"+plan+"\u0000"+line).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    public record Report(int completed,int failed,int waiting) { }
}
