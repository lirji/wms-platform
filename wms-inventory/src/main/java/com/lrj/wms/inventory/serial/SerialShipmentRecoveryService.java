package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSessionFactory;

/** 本地发运已提交，仅恢复原SN的全球终态；网络调用不占本地事务，不再扣减数量。 */
public final class SerialShipmentRecoveryService {
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final SerialShipmentRegistryPort registry;

    public SerialShipmentRecoveryService(SqlSessionFactory sessions,Clock clock,SerialShipmentRegistryPort registry) {
        this.sessions=sessions;this.clock=clock;this.registry=registry;
    }

    /** 每轮至多20项/10秒，单次HTTP另有截止；12次自动尝试后隔离，原事实永不改写。 */
    public SerialRecoveryService.Report execute(String e,String w) {
        if(registry==null) throw new IllegalStateException("未配置真实发运登记调用器");
        int done=0,failed=0;
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        for(int n=0;n<20 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();n++) {
            var row=claim(e,w);if(row==null) break;
            try {
                var result=registry.ship(e,text(row,"sku_id"),text(row,"serial_id"),w,text(row,"shipment_ref"),number(row,"owner_epoch"));
                requireProof(result,e,w,text(row,"sku_id"),text(row,"serial_id"),text(row,"shipment_ref"),number(row,"owner_epoch"));
                String proof=RuntimeMessage.JSON.writeValueAsString(result.get("shipment"));
                if(finish(e,w,row,"DONE",null,proof,0)) done++;
            } catch(RuntimeException failure) {
                String code=failure instanceof SerialRegistryConflictException conflict?conflict.code():"REGISTRY_UNAVAILABLE";
                boolean permanent=failure instanceof SerialRegistryConflictException
                        && !Set.of("SERIAL_STATE_CONFLICT","VERSION_CONFLICT").contains(code);
                long attempts=number(row,"attempts");
                long delay=Math.min(300000L,1000L<<Math.min(attempts,8))+ThreadLocalRandom.current().nextLong(501);
                finish(e,w,row,permanent || attempts>=12?"ISOLATED":"PENDING",code,null,delay);failed++;
            }
        }
        return new SerialRecoveryService.Report(done,failed);
    }

    /** 只接受完整原事实证明；当前身份可能进入后续生命周期，不能用它重建历史发运。 */
    static void requireProof(Map<String,Object> result,String e,String w,String sku,String serial,String ref,long epoch) {
        if(!(result.get("shipment") instanceof Map<?,?> proof)
                || !integerEquals(proof.get("schemaVersion"),1) || !e.equals(proof.get("enterpriseId"))
                || !w.equals(proof.get("warehouseId")) || !sku.equals(proof.get("skuId"))
                || !serial.equals(proof.get("normalizedSerial")) || !ref.equals(proof.get("shipmentRef"))
                || !integerEquals(proof.get("ownerEpoch"),epoch))
            throw new SerialRegistryUnavailableException("原发运证明不完整或不匹配");
    }

    private Map<String,Object> claim(String e,String w) {
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);
            var mapper=session.getMapper(SerialShipmentMapper.class);
            var now=Timestamp.from(clock.instant());var row=mapper.next(e,w,now);
            if(row==null) return null;
            if(number(row,"attempts")>=12) {
                if(mapper.finish(e,w,text(row,"id"),number(row,"claim_epoch"),"ISOLATED","ATTEMPTS_EXHAUSTED",null,now,now)!=1)
                    throw new InventoryException("VERSION_CONFLICT","发运意图隔离竞争");
                session.commit();return null;
            }
            if(mapper.claim(e,w,text(row,"id"),number(row,"claim_epoch"),Timestamp.from(clock.instant().plusSeconds(15)),now)!=1)
                throw new InventoryException("VERSION_CONFLICT","发运意图领取竞争");
            row.put("claim_epoch",number(row,"claim_epoch")+1);row.put("attempts",number(row,"attempts")+1);
            session.commit();return row;
        }
    }

    private boolean finish(String e,String w,Map<String,Object> claimed,String state,String error,String proof,long delay) {
        try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);
            var mapper=session.getMapper(SerialShipmentMapper.class);
            var current=mapper.lock(e,w,text(claimed,"id"));
            // 先锁原意图并核对代际，旧HTTP回执不得触碰本地身份或新的重试周期。
            if(current==null || number(current,"claim_epoch")!=number(claimed,"claim_epoch") || !"RUNNING".equals(current.get("state")))
                return false;
            var now=Timestamp.from(clock.instant());
            if(mapper.finish(e,w,text(claimed,"id"),number(claimed,"claim_epoch"),state,error,proof,
                    Timestamp.from(clock.instant().plusMillis(delay)),now)!=1)
                throw new InventoryException("VERSION_CONFLICT","发运证明写入竞争");
            if("DONE".equals(state)) {
                // 只更新仍属于原离库的观察；后续生命周期不被历史成功回执覆盖。
                mapper.observe(Map.of("e",e,"w",w,"sku",current.get("sku_id"),"serial",current.get("serial_id"),
                        "balance",current.get("balance_id"),"epoch",current.get("owner_epoch"),"now",now));
            }
            session.commit();return true;
        }
    }
    private static boolean integerEquals(Object value,long expected) {
        return (value instanceof Long || value instanceof Integer) && ((Number)value).longValue()==expected;
    }
    private static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    private static String text(Map<String,Object> row,String field) {return (String)row.get(field);}
}
