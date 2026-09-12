package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/** HOLD事实先提交，登记网络调用不持有库存连接；本地放行以领取代际及行版本双重校验。 */
public final class SerialRecoveryService {
    private final SqlSessionFactory sessions;
    private final Clock clock;
    private final SerialRegistryPort receipt;
    private final SerialTransferRegistryPort transfer;
    public SerialRecoveryService(SqlSessionFactory sessions,Clock clock,SerialRegistryPort receipt,SerialTransferRegistryPort transfer) {
        this.sessions=sessions; this.clock=clock; this.receipt=receipt; this.transfer=transfer;
    }

    /** 调用方必须与本地HOLD事实同事务；重复输入只能保留原始库存桶、转移及epoch。 */
    public static void stage(SqlSession session,Clock clock,String e,String w,Map<String,Object> local,String transfer,Long epoch) {
        String serial=String.valueOf(local.get("serial_id")), operation=String.valueOf(local.get("receipt_operation_id"));
        if(local.get("balance_id")==null || transfer!=null && (epoch==null || epoch<0))
            throw new InventoryException("SERIAL_RECOVERY_CONTEXT_REQUIRED","缺少已入账HOLD或转移原始epoch");
        String id=SerialRegistryHttpClient.digest(RuntimeMessage.JSON.writeValueAsString(List.of(e,w,serial,operation)));
        var context=new TreeMap<String,Object>(); context.putAll(Map.of("sku",local.get("sku_id"),"lot",local.get("lot_id"),"balance",local.get("balance_id")));
        context.put("transfer",transfer); context.put("epoch",epoch);
        String hash=SerialRegistryHttpClient.digest(RuntimeMessage.JSON.writeValueAsString(context));
        var row=new HashMap<String,Object>(); row.putAll(Map.of("id",id,"e",e,"w",w,"serial",serial,"sku",local.get("sku_id"),"operation",operation,
                "kind",transfer==null?"RECEIPT":"TRANSFER","hash",hash,"now",Timestamp.from(clock.instant())));
        row.put("transfer",transfer); row.put("epoch",epoch);
        var mapper=session.getMapper(SerialRecoveryMapper.class); mapper.insert(row);
        var existing=mapper.lock(e,w,id);
        if(existing==null || !hash.equals(existing.get("context_hash"))) throw new InventoryException("SERIAL_OPERATION_MISMATCH","登记恢复必须保留原始库存及转移上下文");
    }

    /** 每仓每次至多20条、20秒；每条最多12次自动尝试，失败保留HOLD与可见隔离状态。 */
    public Report execute(String e,String w) {
        return execute(e,w,20);
    }
    /** 调度器可为收货和源释放各分配独立预算，单类积压不阻断另一类。 */
    public Report execute(String e,String w,int seconds) {
        if(seconds<1 || seconds>20) throw new IllegalArgumentException("登记恢复预算必须在1至20秒内");
        if(receipt==null || transfer==null) throw new IllegalStateException("未配置真实登记调用器");
        int completed=0,failed=0; long deadline=System.nanoTime()+java.time.Duration.ofSeconds(seconds).toNanos();
        for(int n=0;n<20 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();n++) {
            Map<String,Object> intent=claim(e,w); if(intent==null) break;
            String id=String.valueOf(intent.get("id")),serial=String.valueOf(intent.get("serial_id"));
            long epoch=number(intent,"claim_epoch");
            try {
                Map<String,Object> local;
                try(var session=sessions.openSession()) { local=session.getMapper(LocalSerialMapper.class).get(e,w,serial); }
                if(!matches(local,intent) || !Set.of("HOLD_RECEIVED","EXCEPTION","RECEIVING","AUTHORIZED").contains(local.get("state"))) {
                    finish(e,w,intent,"SUPERSEDED","LOCAL_INTENT_CHANGED",0); continue;
                }
                if("AUTHORIZED".equals(local.get("state"))) { finish(e,w,intent,"DONE",null,0); completed++; continue; }
                String sku=String.valueOf(intent.get("sku_id")),op=String.valueOf(intent.get("operation_id"));
                Map<String,Object> active;
                if("TRANSFER".equals(intent.get("kind"))) {
                    String transferId=String.valueOf(intent.get("transfer_id"));
                    transfer.startReceiving(e,sku,serial,transferId,w,op,number(intent,"from_epoch"));
                    active=transfer.confirmDestination(e,sku,serial,transferId,w,op);
                } else { receipt.claim(e,sku,serial,w,op); active=receipt.activate(e,sku,serial,w,op); }
                if(!"ACTIVE".equals(active.get("state")) || !w.equals(active.get("ownerWarehouseId"))
                        || !op.equals(active.get("receiptOperationId")) || !serial.equals(active.get("normalizedSerial"))
                        || !(active.get("ownerEpoch") instanceof Number observed) || observed.longValue()<number(local,"owner_epoch")
                        || "TRANSFER".equals(intent.get("kind")) && observed.longValue()!=number(intent,"from_epoch")+1)
                    throw new SerialRegistryUnavailableException("登记授权响应不匹配原始意图");
                try(var session=sessions.openSession(false)) {
                    requireWritable(session,e,w);
                    var locals=session.getMapper(LocalSerialMapper.class); var current=locals.lock(e,w,serial);
                    var mapper=session.getMapper(SerialRecoveryMapper.class); var owned=mapper.lock(e,w,id);
                    if(owned==null || number(owned,"claim_epoch")!=epoch || !"RUNNING".equals(owned.get("state"))) { session.rollback(); continue; }
                    if(!matches(current,intent) || number(current,"version")!=number(local,"version"))
                        throw new InventoryException("VERSION_CONFLICT","在途登记期间本地状态已变化，拒绝旧执行器放行");
                    if(locals.updateState(e,w,serial,String.valueOf(current.get("balance_id")),"AUTHORIZED","ACTIVE",null,observed.longValue(),Timestamp.from(clock.instant()))!=1
                            || mapper.finish(e,w,id,epoch,"DONE",null,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()))!=1)
                        throw new InventoryException("VERSION_CONFLICT","登记授权与恢复进度必须同事务写入");
                    session.commit(); completed++;
                }
            } catch(RuntimeException error) {
                String code=error instanceof SerialRegistryConflictException conflict?conflict.code():error instanceof InventoryException inventory?inventory.code():"REGISTRY_UNAVAILABLE";
                boolean permanent=error instanceof SerialRegistryConflictException && !Set.of("SERIAL_STATE_CONFLICT","VERSION_CONFLICT").contains(code);
                int attempt=((Number)intent.get("attempts")).intValue();
                long delay=Math.min(300_000L,1000L<<Math.min(attempt,8))+ThreadLocalRandom.current().nextLong(501);
                finish(e,w,intent,permanent || attempt>=12?"ISOLATED":"PENDING",code,delay); failed++;
            }
        }
        return new Report(completed,failed);
    }
    private Map<String,Object> claim(String e,String w) {
        try(var session=sessions.openSession(false)) {
            requireWritable(session,e,w);
            var mapper=session.getMapper(SerialRecoveryMapper.class); var now=Timestamp.from(clock.instant());
            var row=mapper.next(e,w,now); if(row==null) return null;
            if(number(row,"attempts")>=12) { mapper.finish(e,w,String.valueOf(row.get("id")),number(row,"claim_epoch"),"ISOLATED","ATTEMPTS_EXHAUSTED",now,now); session.commit(); return null; }
            if(mapper.claim(e,w,String.valueOf(row.get("id")),number(row,"claim_epoch"),Timestamp.from(clock.instant().plusSeconds(15)),now)!=1)
                throw new InventoryException("VERSION_CONFLICT","登记恢复领取竞争");
            row.put("claim_epoch",number(row,"claim_epoch")+1); row.put("attempts",number(row,"attempts")+1); session.commit(); return row;
        }
    }
    private void finish(String e,String w,Map<String,Object> intent,String state,String error,long delay) {
        try(var session=sessions.openSession(false)) {
            requireWritable(session,e,w);
            session.getMapper(SerialRecoveryMapper.class).finish(e,w,String.valueOf(intent.get("id")),number(intent,"claim_epoch"),state,error,
                    Timestamp.from(clock.instant().plusMillis(delay)),Timestamp.from(clock.instant())); session.commit();
        }
    }
    /** 所有恢复写入与仓切流使用同一短事务路由锁；停写期间不领取、不补授权、不人工重排。 */
    public static void requireWritable(SqlSession session,String e,String w) {
        String state=session.getMapper(SerialRecoveryMapper.class).routeState(e,w);
        if(state!=null && !"ACTIVE".equals(state)) throw new InventoryException("STALE_ROUTE","仓迁移停写或已切走，恢复执行器停止写入");
    }
    private static boolean matches(Map<String,Object> local,Map<String,Object> intent) {
        return local!=null && intent.get("sku_id").equals(local.get("sku_id")) && intent.get("operation_id").equals(local.get("receipt_operation_id"))
                && Objects.equals(intent.get("transfer_id"),local.get("transfer_id")) && local.get("balance_id")!=null;
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public record Report(int completed,int failed) { }
}
