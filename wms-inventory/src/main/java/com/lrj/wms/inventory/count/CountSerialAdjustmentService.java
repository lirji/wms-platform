package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.serial.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 固定已审批行的首次调整身份，凭证未齐备时只提交待办，不在本地事务里访问登记服务。 */
public final class CountSerialAdjustmentService {
    private final SqlSession session;
    private final Clock clock;
    public CountSerialAdjustmentService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 序列行固定完整观察及本地快照；数量行返回null，继续既有数量调整路径。 */
    public Map<String,Object> stage(String e,String w,String planId,String lineId,String operation,String actor) {
        SerialRecoveryService.requireWritable(session,e,w);
        var counts=session.getMapper(CountMapper.class);var plan=counts.lockPlan(e,w,planId);
        var line=counts.lockLine(e,w,planId,lineId);
        if(line==null) throw new InventoryException("COUNT_LINE_NOT_FOUND","原盘点行不存在");
        var mapper=session.getMapper(CountSerialMapper.class);var existing=mapper.lockAdjustment(e,w,lineId);
        if(existing!=null) return existing;
        requireApproved(plan);
        String observation=counts.latestObservationId(e,w,lineId);
        var observed=observation==null?null:counts.lockObservation(e,w,observation);
        if(observed==null) throw new InventoryException("COUNT_OBSERVATION_REQUIRED","调整缺少完整原观察");
        if(!"SERIAL".equals(observed.get("observation_kind"))) {
            if(counts.countLineSerials(e,w,lineId)>0) throw new InventoryException("COUNT_OBSERVATION_CONTEXT_REQUIRED","旧序列观察缺少不可变输入，需要核实");
            return null;
        }
        if(operation==null || operation.isBlank() || operation.length()>64 || actor==null || actor.isBlank() || actor.length()>64)
            throw new InventoryException("INVALID_OPERATION","调整原操作和主体必须合法");
        var inventory=session.getMapper(InventoryMapper.class);
        CountService.requireCountGate(inventory,counts,e,w,planId,String.valueOf(line.get("location_id")),InventoryCodes.CMD_COUNT_ADJUST);
        var balance=inventory.lockBalanceById(e,w,String.valueOf(line.get("balance_id")));
        if(balance==null) throw new InventoryException("RESOURCE_NOT_FOUND","原盘点桶不存在");
        var input=RuntimeMessage.JSON.readValue(String.valueOf(observed.get("serial_input_json")),com.lrj.wms.contract.messaging.SerialCountObservation.class);
        input.requireQuantity(new BigDecimal(line.get("counted_qty").toString()));
        var sightings=counts.listObservationSerials(e,w,observation);
        // 预占尚未绑定具体SN时，不能猜测盘亏的是自由身份；尤其净数量不变的替换也会撤销原授权。
        requireUnoccupiedMissing(balance,sightings.stream().anyMatch(row -> "MISSING".equals(row.get("presence_code"))));
        if(sightings.size()>400) throw new InventoryException("COUNT_SERIAL_BATCH_LIMIT","盘点身份超过恢复预算");
        var locals=session.getMapper(LocalSerialMapper.class);var identities=new ArrayList<Map<String,Object>>();
        var baseline=new TreeSet<String>();var seen=new TreeSet<String>();
        for(var sight:sightings) {
            String serial=String.valueOf(sight.get("normalized_serial")),kind=String.valueOf(sight.get("presence_code"));
            var local=locals.lock(e,w,serial);
            if(!Set.of("FOUND","MISSING","PRESENT").contains(kind)) throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","未知身份观察类型");
            if(!"MISSING".equals(kind)) seen.add(serial);
            if("FOUND".equals(kind)) {
                if(local!=null && (!"MISSING".equals(local.get("state")) || !balance.get("sku_id").equals(local.get("sku_id"))))
                    throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","盘盈身份已在其他本地生命周期或SKU占用");
            } else {
                baseline.add(serial);
                if(local==null || !balance.get("id").equals(local.get("balance_id")) || !balance.get("sku_id").equals(local.get("sku_id")) || !balance.get("lot_id").equals(local.get("lot_id")))
                    throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","原实物身份已改变桶或商品");
                if(!"AUTHORIZED".equals(local.get("state")) || !"ACTIVE".equals(local.get("registry_state")))
                    throw new InventoryException("COUNT_REGISTRY_PENDING","实物身份须先完成原收货或转移登记，再固定盘点调整代际");
            }
            var item=new TreeMap<String,Object>();item.put("serial",serial);item.put("kind",kind);item.put("local",snapshot(local));identities.add(item);
        }
        if(!seen.equals(new TreeSet<>(input.serialIds()))) throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","观察子行与已固化完整输入不同");
        requireBaseline(counts,e,w,String.valueOf(balance.get("id")),baseline);
        if(new BigDecimal(balance.get("on_hand_qty").toString()).compareTo(BigDecimal.valueOf(baseline.size()))!=0)
            throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","原数量和实物身份不一致，不能用调整掩盖既有漂移");
        var context=new TreeMap<String,Object>();context.putAll(Map.of("schemaVersion",1,"balanceId",balance.get("id"),"skuId",balance.get("sku_id"),"lotId",balance.get("lot_id"),
                "quantity",line.get("counted_qty").toString(),"onHand",balance.get("on_hand_qty").toString(),"identities",identities));
        String json=RuntimeMessage.JSON.writeValueAsString(context),id=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("COUNT_ADJUSTMENT",e,w,planId,lineId)));
        var now=Timestamp.from(clock.instant());var row=new HashMap<String,Object>(Map.of("id",id,"e",e,"w",w,"plan",planId,"line",lineId,"observation",observation,"operation",operation,"actor",actor,"context",json,"hash",RuntimeMessage.hash(json)));row.put("now",now);
        mapper.insertAdjustment(row);
        for(var item:identities) {
            if("PRESENT".equals(item.get("kind"))) continue;
            String serial=item.get("serial").toString();var child=new HashMap<String,Object>();
            child.putAll(Map.of("id",RuntimeMessage.hash(id+"\n"+serial),"e",e,"w",w,"adjustment",id,"plan",planId,"serial",serial,"sku",balance.get("sku_id"),"operation",operation,"kind",item.get("kind"),"now",now));
            var local=(Map<?,?>)item.get("local");child.put("epoch",local==null?null:local.get("owner_epoch"));mapper.insertSerial(child);
        }
        mapper.ready(e,w,planId,now);return mapper.lockAdjustment(e,w,lineId);
    }

    /** 在同一原行事务中消费已持久化结果，先验证快照，再提交数量、身份和APPLIED屏障。 */
    public Map<String,Object> apply(String e,String w,String plan,String line,String operation,String actor) {
        var intent=stage(e,w,plan,line,operation,actor);
        if(intent==null) return new CountService(session,clock).applyLine(e,w,plan,line,operation,actor);
        if("APPLIED".equals(intent.get("state"))) return CountService.lineView(session.getMapper(CountMapper.class).lockLine(e,w,plan,line));
        if(!"READY".equals(intent.get("state"))) return Map.of("lineId",line,"adjustmentId",intent.get("id"),"status","REGISTRY_PENDING");
        validateLocal(e,w,intent);
        var rows=session.getMapper(CountSerialMapper.class).serials(e,w,intent.get("id").toString());
        Map<String,Map<String,Object>> proofs=new HashMap<>();
        for(var row:rows) {
            if(!"DONE".equals(row.get("state")) || row.get("result_json")==null) throw new InventoryException("COUNT_REGISTRY_PENDING","身份登记凭证尚未齐备");
            @SuppressWarnings("unchecked") var proof=(Map<String,Object>)RuntimeMessage.JSON.readValue(row.get("result_json").toString(),Map.class);
            proofs.put(row.get("serial_id").toString(),proof);
        }
        String original=intent.get("operation_id").toString();
        var result=new CountService(session,clock,new SavedResults(proofs)).applyLine(e,w,plan,line,original,intent.get("actor_id").toString());
        if(session.getMapper(CountSerialMapper.class).applied(e,w,line,Timestamp.from(clock.instant()))!=1)
            throw new InventoryException("VERSION_CONFLICT","盘点数量、身份与恢复进度必须一起提交");
        return result;
    }

    @SuppressWarnings("unchecked")
    private void validateLocal(String e,String w,Map<String,Object> intent) {
        var counts=session.getMapper(CountMapper.class);String plan=intent.get("plan_id").toString(),lineId=intent.get("line_id").toString();
        requireApproved(counts.lockPlan(e,w,plan));var line=counts.lockLine(e,w,plan,lineId);
        CountService.requireCountGate(session.getMapper(InventoryMapper.class),counts,e,w,plan,line.get("location_id").toString(),InventoryCodes.CMD_COUNT_ADJUST);
        var context=(Map<String,Object>)RuntimeMessage.JSON.readValue(intent.get("context_json").toString(),Map.class);
        if(!intent.get("observation_id").equals(counts.latestObservationId(e,w,lineId))
                || !context.get("balanceId").equals(line.get("balance_id"))
                || new BigDecimal(context.get("quantity").toString()).compareTo(new BigDecimal(line.get("counted_qty").toString()))!=0)
            throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","原审批观察或目标数量已改变");
        var balance=session.getMapper(InventoryMapper.class).lockBalanceById(e,w,context.get("balanceId").toString());
        if(balance==null || new BigDecimal(balance.get("on_hand_qty").toString()).compareTo(new BigDecimal(context.get("onHand").toString()))!=0)
            throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","登记期间原桶实物量已改变");
        requireUnoccupiedMissing(balance,((List<Map<String,Object>>)context.get("identities")).stream().anyMatch(row -> "MISSING".equals(row.get("kind"))));
        var locals=session.getMapper(LocalSerialMapper.class);var baseline=new TreeSet<String>();
        for(var item:(List<Map<String,Object>>)context.get("identities")) {
            String serial=item.get("serial").toString(),kind=item.get("kind").toString();var before=(Map<String,Object>)item.get("local");var current=snapshot(locals.lock(e,w,serial));
            if(!"FOUND".equals(kind)) baseline.add(serial);
            if(!RuntimeMessage.JSON.writeValueAsString(before==null?null:new TreeMap<>(before)).equals(RuntimeMessage.JSON.writeValueAsString(current)))
                throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","在途登记期间原身份发生变化，拒绝旧凭证落地");
        }
        requireBaseline(counts,e,w,context.get("balanceId").toString(),baseline);
    }
    private static void requireUnoccupiedMissing(Map<String,Object> balance,boolean missing) {
        if(missing && (new BigDecimal(balance.get("reserved_qty").toString()).signum()>0
                || new BigDecimal(balance.get("free_execution_claim_qty").toString()).signum()>0))
            throw new InventoryException("COUNT_SERIAL_RESERVED_CONFLICT","盘亏涉及已占用桶，先释放或重分配后再登记身份，不能等量替换绕过");
    }
    private static Map<String,Object> snapshot(Map<String,Object> local) {
        if(local==null) return null;
        var result=new TreeMap<String,Object>();for(String field:List.of("serial_id","sku_id","lot_id","balance_id","receipt_operation_id","owner_epoch","state","registry_state","transfer_id","source_release_ref")) result.put(field,local.get(field));return result;
    }
    private static void requireBaseline(CountMapper counts,String e,String w,String balance,Set<String> baseline) {
        var rows=counts.observationLocals(e,w,balance);var actual=new TreeSet<String>();for(var row:rows) actual.add(row.get("serial_id").toString());
        if(rows.size()>200 || !actual.equals(baseline)) throw new InventoryException("COUNT_SERIAL_CONTEXT_CONFLICT","原盘点实物身份集合已改变");
    }
    static void requireApproved(Map<String,Object> plan) {
        if(plan==null || plan.get("approval_id")==null || plan.get("approved_by")==null || !Set.of("APPROVED","APPLYING").contains(plan.get("status")))
            throw new InventoryException("COUNT_STATE_CONFLICT","逐身份恢复只能执行原已审批计划");
    }
    /** 只读取已核验的持久凭证；保留旧领域接口以渐进迁移，不转发任何网络请求。 */
    private record SavedResults(Map<String,Map<String,Object>> proofs) implements SerialCountRegistryPort {
        private Map<String,Object> proof(String serial) {var result=proofs.get(serial);if(result==null) throw new InventoryException("COUNT_REGISTRY_PENDING","缺少该身份的原登记凭证");return result;}
        public Map<String,Object> markMissing(String e,String sku,String sn,String w,String ref,long epoch) {return proof(sn);}
        public Map<String,Object> claimFound(String e,String sku,String sn,String w,String op) {return proof(sn);}
        public Map<String,Object> activateFound(String e,String sku,String sn,String w,String op) {return proof(sn);}
        public Map<String,Object> get(String e,String sku,String sn) {throw new UnsupportedOperationException("盘点落地只能使用原动作凭证");}
    }
}
