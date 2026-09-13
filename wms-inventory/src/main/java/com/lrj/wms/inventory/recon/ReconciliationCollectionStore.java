package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.serial.SerialRecoveryService;
import com.lrj.wms.runtime.db.DatabaseInstants;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 本地采集生命周期，调用方提交事务；网络必须发生在领取提交之后和检查点事务之前。 */
public final class ReconciliationCollectionStore {
    private final SqlSession session;
    private final Clock clock;
    public ReconciliationCollectionStore(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 每仓一个活动窗口；冻结历史、建立检查点和请求审计同事务。 */
    public Map<String,Object> request(String e,String w,String id,Instant cutoff,String initialProgress,String actor) {
        require(e,64);require(w,64);require(id,64);require(actor,128);progress(initialProgress);
        if(cutoff==null || cutoff.isAfter(clock.instant()) || cutoff.getNano()%1000!=0) throw new IllegalArgumentException("无效关窗时刻");
        SerialRecoveryService.requireWritable(session,e,w);
        session.getMapper(InventoryMapper.class).ensureHistoryGuard(UUID.randomUUID().toString(),e,w);
        var mapper=session.getMapper(ReconciliationCollectionMapper.class);var guard=mapper.guard(e,w);
        session.getMapper(ReconciliationMapper.class).upsertCutoff(UUID.randomUUID().toString(),e,w,id,Timestamp.from(cutoff),null,null,null,0,now());
        var row=mapper.lock(e,w,id);
        if(!cutoff.equals(DatabaseInstants.require(row.get("closed_at")))) throw conflict();
        if(!"UNREQUESTED".equals(row.get("collection_state"))) return row;
        if(guard.get("active_cutoff_id")!=null && !id.equals(guard.get("active_cutoff_id")))
            throw new JobRunException("WINDOW_ACTIVE","本仓已有活动采集窗口");
        requireOne(mapper.activate(e,w,id,Timestamp.from(cutoff)));
        requireOne(mapper.start(e,w,id,initialProgress,now()));
        requireOne(mapper.audit(e,w,id,UUID.randomUUID().toString(),"REQUEST",actor,"请求可信关窗",0,now()));
        return mapper.lock(e,w,id);
    }

    /** 15秒租约，每个检查点最多12次连续尝试；成功推进不消耗后续页预算。 */
    public Map<String,Object> claim(String e,String w,String id) {
        var mapper=lockedMapper(e,w);var row=mapper.lock(e,w,id);
        if(row==null || !Set.of("PENDING","RUNNING").contains(row.get("collection_state"))
                || row.get("next_attempt_at")==null || DatabaseInstants.require(row.get("next_attempt_at")).isAfter(clock.instant())) return null;
        long epoch=number(row,"claim_epoch");
        if(number(row,"collection_attempts")>=12) {
            requireOne(mapper.checkpoint(e,w,id,epoch,text(row,"collection_progress"),"ISOLATED",false,"ATTEMPTS_EXHAUSTED",now(),now()));
            return null;
        }
        requireOne(mapper.claim(e,w,id,epoch,Timestamp.from(clock.instant().plusSeconds(15)),now()));
        return mapper.lock(e,w,id);
    }

    /** 持锁确认原领取代际，旧远程响应必须在核对/写事实之前被拒绝。 */
    public Map<String,Object> currentClaim(String e,String w,String id,long epoch) {
        var row=lockedMapper(e,w).lock(e,w,id);
        return row!=null && number(row,"claim_epoch")==epoch && "RUNNING".equals(row.get("collection_state")) ? row:null;
    }

    /** 只能推进到待继续或隔离，不能由字符串检查点直接签发完整证明。 */
    public boolean checkpoint(String e,String w,String id,long epoch,String json,boolean advanced,String error) {
        progress(json);
        if(error!=null && !error.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("无效采集错误码");
        var row=currentClaim(e,w,id,epoch);if(row==null) return false;
        long attempts=number(row,"collection_attempts");
        String state=!advanced && attempts>=12?"ISOLATED":"PENDING";
        long delay=advanced?0:Math.min(300,1L<<Math.min(attempts,8));
        requireOne(session.getMapper(ReconciliationCollectionMapper.class).checkpoint(e,w,id,epoch,json,state,advanced,error,
                Timestamp.from(clock.instant().plusSeconds(delay)),now()));
        return true;
    }

    /** 审计重排保留原检查点，取消只停止采集，不降低历史上界或撤销业务写入。 */
    public void control(String e,String w,String id,long epoch,String action,String actor,String reason) {
        require(actor,128);require(reason,512);
        if(!Set.of("RETRY","CANCEL").contains(action)) throw new IllegalArgumentException("无效采集控制动作");
        var mapper=lockedMapper(e,w);var row=mapper.lock(e,w,id);
        if(row==null || number(row,"claim_epoch")!=epoch || !Set.of("PENDING","RUNNING","ISOLATED").contains(row.get("collection_state"))) throw conflict();
        if("RETRY".equals(action) && !"ISOLATED".equals(row.get("collection_state"))) throw conflict();
        requireOne(mapper.control(e,w,id,epoch,"RETRY".equals(action)?"PENDING":"CANCELLED",now()));
        requireOne(mapper.audit(e,w,id,UUID.randomUUID().toString(),action,actor,reason,epoch+1,now()));
        if("CANCEL".equals(action)) requireOne(mapper.release(e,w,id));
    }
    private ReconciliationCollectionMapper lockedMapper(String e,String w) {
        SerialRecoveryService.requireWritable(session,e,w);
        var mapper=session.getMapper(ReconciliationCollectionMapper.class);
        if(mapper.guard(e,w)==null) throw new JobRunException("CUTOFF_MISSING","采集范围不存在");
        return mapper;
    }
    private Timestamp now() {return Timestamp.from(clock.instant());}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static String text(Map<String,Object> row,String key) {return String.valueOf(row.get(key));}
    private static void require(String text,int max) {if(text==null || text.isBlank() || text.length()>max) throw new IllegalArgumentException("无效采集范围或审计输入");}
    private static void progress(String json) {
        if(json==null || json.length()>8192 || !com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(json).isObject())
            throw new IllegalArgumentException("采集检查点必须为有界对象");
    }
    private static void requireOne(int count) {if(count!=1) throw conflict();}
    private static JobRunException conflict() {return new JobRunException("VERSION_CONFLICT","采集状态、原窗口或领取代际冲突");}
}
