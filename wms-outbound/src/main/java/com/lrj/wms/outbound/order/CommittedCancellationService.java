package com.lrj.wms.outbound.order;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/** 先持久化原订单门禁，再按原桶生成CANCEL；业务效果和回执复用现有T1/T2/T3。 */
public final class CommittedCancellationService {
    private final SqlSession session;
    private final Clock clock;
    public CommittedCancellationService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 同订单锁串行授权与取消；即使取消先于建单消息到达也占住原分配身份。 */
    public void accept(RuntimeMessage message) {
        if(!"wms-fulfillment".equals(message.sourceService()) || !CommittedCancellation.EVENT.equals(message.eventType()))
            throw new MessageRejectedException("INVALID_CANCELLATION_SOURCE");
        var value=RuntimeMessage.JSON.treeToValue(message.payload(),CommittedCancellation.class);
        var request=value.request();
        if(!message.enterpriseId().equals(request.enterpriseId()) || !message.warehouseId().equals(request.warehouseId())
                || !message.aggregateId().equals(request.attemptId()) || message.aggregateVersion()!=1)
            throw new MessageRejectedException("CANCELLATION_SCOPE_MISMATCH");
        var lines=new TreeMap<String,Map<String,Object>>();
        for(var line:request.lines()) {
            var row=lines.computeIfAbsent(line.orderLineId(),id->new LinkedHashMap<>(Map.of("orderLineId",id,
                    "skuId",line.skuId(),"baseUnit",line.baseUnit(),"qty",BigDecimal.ZERO)));
            if(!line.skuId().equals(row.get("skuId")) || !line.baseUnit().equals(row.get("baseUnit")))
                throw new MessageRejectedException("CANCELLATION_LINE_MISMATCH");
            row.put("qty",decimal(row.get("qty")).add(line.qty()));
        }
        var order=new OutboundOrderService(session,clock).createFromAllocation(request.enterpriseId(),request.warehouseId(),
                request.allocationId(),request.attemptId(),request.ownerId(),null,List.copyOf(lines.values()));
        var mapper=session.getMapper(OutboundOrderMapper.class);
        String payload=RuntimeMessage.JSON.writeValueAsString(value),hash=RuntimeMessage.contentHash(payload),o=order.get("id").toString();
        mapper.insertCancellation(Map.of("e",request.enterpriseId(),"w",request.warehouseId(),"a",request.attemptId(),
                "id",value.cancellationId(),"o",o,"payload",payload,"hash",hash,"now",now()));
        var original=mapper.cancellation(request.enterpriseId(),request.warehouseId(),o);
        if(!hash.equals(original.get("payload_hash"))) throw new MessageRejectedException("CANCELLATION_IDENTITY_MISMATCH");
    }

    /** 每轮最多一原单/200桶，只有本地短事务；网络发布由既有Outbox独立进行。 */
    public static void recoverOne(SqlSessionFactory sessions,Clock clock) {
        Map<String,Object> candidate;
        try(var session=sessions.openSession()) {candidate=session.getMapper(OutboundOrderMapper.class).dueCancellation(Timestamp.from(clock.instant()));}
        if(candidate==null) return;
        try(var session=sessions.openSession(false)) {
            new CommittedCancellationService(session,clock).recover(candidate.get("enterprise_id").toString(),
                    candidate.get("warehouse_id").toString(),candidate.get("order_id").toString());
            session.commit();
        } catch(RuntimeException failed) {
            // 原T1失败已回滚；另一个短事务只记录退避，避免同一坏单每250ms阻塞整个恢复队列。
            try(var session=sessions.openSession(false)) {
                var mapper=session.getMapper(OutboundOrderMapper.class);
                String e=candidate.get("enterprise_id").toString(),w=candidate.get("warehouse_id").toString(),o=candidate.get("order_id").toString();
                mapper.lockOrder(e,w,o);var row=mapper.cancellation(e,w,o);
                if(row!=null && "PROCESSING".equals(row.get("state"))) {
                    var update=new HashMap<String,Object>();update.put("e",e);update.put("w",w);update.put("o",o);update.put("state","PROCESSING");
                    update.put("error",failed instanceof OutboundException failure?failure.code():"COMPENSATION_STEP_FAILED");
                    update.put("now",Timestamp.from(clock.instant()));
                    update.put("next",Timestamp.from(clock.instant().plusSeconds(Math.min(300,5L<<Math.min(6,((Number)row.get("version")).longValue())))));
                    if(mapper.advanceCancellation(update)!=1) throw new IllegalStateException("补偿失败进度竞争");
                }
                session.commit();
            }
        }
    }

    /** 确认原回执之前不把取消标记完成；设备未知不释放、不删除原任务或实物事实。 */
    public void recover(String e,String w,String o) {
        var mapper=session.getMapper(OutboundOrderMapper.class);
        mapper.lockOrder(e,w,o);
        var current=mapper.cancellation(e,w,o);
        if(current==null || !"PROCESSING".equals(current.get("state"))) return;
        var value=RuntimeMessage.JSON.readValue(current.get("payload").toString(),CommittedCancellation.class);
        String error=null,state="PROCESSING";
        {
            boolean pending=false;
            for(var bucket:value.request().lines()) {
                var line=mapper.lockLineByOrderLine(e,w,o,bucket.orderLineId());
                if(mapper.cancellationUncertain(e,w,o,line.get("id").toString())>0) {error="PHYSICAL_OR_POSTING_UNKNOWN";continue;}
                var usage=mapper.cancellationUsage(e,w,o,line.get("id").toString(),bucket.sourceLocationId(),bucket.lotId());
                BigDecimal left=bucket.qty().subtract(decimal(usage.get("used_qty")));
                if(left.signum()<0) {error="ORIGINAL_BUCKET_FACT_MISMATCH";break;}
                if(decimal(usage.get("pending")).signum()>0) {pending=true;continue;}
                if(left.signum()==0) continue;
                String command=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("cancel-compensation",e,w,
                        value.request().attemptId(),bucket.orderLineId(),bucket.sourceLocationId(),bucket.lotId())));
                var result=new OutboundOrderService(session,clock).cancelUnpickedInternal(e,w,o,bucket.orderLineId(),command,
                        value.actorId(),left,value.cancellationId());
                if(!Boolean.TRUE.equals(result.get("replayed")) && mapper.bindCompensation(e,w,command,value.cancellationId())!=1)
                    throw new OutboundException("VERSION_CONFLICT","补偿身份必须与原命令同时冻结");
                var context=new StockPostingContext(o,value.request().ownerId(),bucket.skuId(),bucket.baseUnit(),
                        bucket.sourceLocationId(),null,bucket.lotId(),"GOOD",value.request().allocationId(),value.request().attemptId());
                new SourceCommandContextStore(session).bindOutbound(e,w,command,context,bucket.orderLineId(),Boolean.TRUE.equals(result.get("replayed")));
                pending=true;
            }
            if(error==null && pending) error="CANCEL_POSTING_PENDING";
            if(error==null) {
                var lines=mapper.listLines(e,w,o);
                boolean executed=lines.stream().anyMatch(line->decimal(line.get("picked_physical_qty")).signum()>0);
                state=executed?"PARTIALLY_COMPENSATED":"COMPLETED";
                String event=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(CommittedCancellation.RESULT,e,w,value.request().attemptId())));
                var body=RuntimeMessage.JSON.writeValueAsString(Map.of("schemaVersion",1,"attemptId",value.request().attemptId(),
                        "cancellationId",value.cancellationId(),"state",state,"releasedQty",lines.stream().map(line->decimal(line.get("cancelled_posted_qty"))).reduce(BigDecimal.ZERO,BigDecimal::add),
                        "executedQty",lines.stream().map(line->decimal(line.get("picked_physical_qty"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
                session.getMapper(com.lrj.wms.outbound.protocol.SourceMapper.class).insertOutbox(event,e,w,value.request().attemptId(),CommittedCancellation.RESULT,body,now());
            }
        }
        var update=new HashMap<String,Object>();update.put("e",e);update.put("w",w);update.put("o",o);update.put("state",state);
        update.put("error",error);update.put("now",now());
        long version=((Number)current.get("version")).longValue();
        update.put("next",Timestamp.from(clock.instant().plusSeconds(Math.min(300,5L<<Math.min(6,version)))));
        if(mapper.advanceCancellation(update)!=1) throw new OutboundException("VERSION_CONFLICT","补偿进度竞争");
    }
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private static BigDecimal decimal(Object value){return new BigDecimal(value.toString());}
}
