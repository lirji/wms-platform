package com.lrj.wms.outbound.order;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 来源身份与实际PICK命令同事务占用，只有原库存回执赋予逐身份发运额度。 */
public final class OutboundSerialService {
    private final SqlSession session;
    private final Clock clock;
    public OutboundSerialService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 原命令完整选择已绑定，逐身份唯一约束防其他任务重复消费。 */
    public void claim(String e,String w,String command,String task,String line,String orderLine,StockPostingContext context,
            SerialExecutionSelection selection,boolean replayed) {
        var mapper=session.getMapper(OutboundSerialMapper.class);
        String hash=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(e,w,command,task,line,orderLine,context)));
        for(var identity:selection.identities()) {
            var existing=mapper.lock(e,w,command,identity.serialId());
            if(existing!=null) {
                if(!hash.equals(existing.get("context_hash")) || identity.ownerEpoch().longValue()!=((Number)existing.get("owner_epoch")).longValue())
                    throw new OutboundException("SERIAL_PICK_CONFLICT","原拣货身份上下文或归属代际改变");
                continue;
            }
            if(replayed) throw new OutboundException("SERIAL_PICK_CONTEXT_REQUIRED","原命令缺少身份事实，不能重放时补造");
            var row=new HashMap<String,Object>();row.putAll(Map.of("id",RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(e,w,command,identity.serialId()))),
                    "e",e,"w",w,"command",command,"task",task,"line",line,"orderLine",orderLine,"order",context.documentId(),"allocation",context.allocationId(),"attempt",context.allocationAttemptId()));
            row.putAll(Map.of("sku",context.skuId(),"serial",identity.serialId(),"epoch",identity.ownerEpoch(),"source",context.sourceLocationId(),"target",context.targetLocationId(),"lot",context.lotId(),"hash",hash,"now",Timestamp.from(clock.instant())));
            try {if(mapper.insert(row)!=1) throw new OutboundException("SERIAL_PICK_CONFLICT","身份占用与拣货实物必须一起提交");}
            catch(RuntimeException error) {if(com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(error)) throw new OutboundException("SERIAL_PICK_CONFLICT","该代际SN已被其他原拣货任务占用");throw error;}
        }
    }

    /** 已去重原PICK回执才标记身份；已释放的历史事实不能被迟到回执拉回。 */
    public void posted(String e,String w,String command,SerialExecutionSelection selection) {
        var mapper=session.getMapper(OutboundSerialMapper.class);
        for(var identity:selection.identities()) {
            var row=mapper.lock(e,w,command,identity.serialId());
            if(row==null || identity.ownerEpoch().longValue()!=((Number)row.get("owner_epoch")).longValue())
                throw new OutboundException("SERIAL_PICK_CONTEXT_REQUIRED","原拣货回执缺少匹配的身份事实");
            if("PICKED".equals(row.get("state"))) continue;
            if(mapper.posted(e,w,row.get("id").toString(),((Number)row.get("version")).longValue(),Timestamp.from(clock.instant()))!=1)
                throw new OutboundException("SERIAL_PICK_CONFLICT","原身份回执不能覆盖已释放状态");
        }
    }
    /** 数量不能替代原SN发运额度，同事务锁定原行、暂存桶及归属代际。 */
    public void claimShipment(String e,String w,String command,String line,StockPostingContext context,SerialExecutionSelection selection,boolean replay) {
        var mapper=session.getMapper(OutboundSerialMapper.class);
        for(var identity:selection.identities()) {
            var input=new HashMap<String,Object>(Map.of("e",e,"w",w,"line",line,"sku",context.skuId(),"serial",identity.serialId(),"epoch",identity.ownerEpoch()));
            var row=mapper.lockForShipment(input);
            if(row==null || !context.allocationId().equals(row.get("allocation_id")) || !context.allocationAttemptId().equals(row.get("attempt_id"))
                    || !context.sourceLocationId().equals(row.get("target_location_id")) || !context.lotId().equals(row.get("lot_id")))
                throw new OutboundException("SERIAL_SHIP_CONFLICT","所选SN不属于原订单行或暂存桶");
            if(command.equals(row.get("shipment_command_id"))) continue;
            if(replay || !"PICKED".equals(row.get("state")) || row.get("shipment_command_id")!=null)
                throw new OutboundException("SERIAL_SHIP_CONFLICT","身份未完成拣货回执或已被其他发运占用");
            input.putAll(Map.of("id",row.get("id"),"version",row.get("version"),"command",command,"now",Timestamp.from(clock.instant())));
            if(mapper.claimShipment(input)!=1) throw new OutboundException("SERIAL_SHIP_CONFLICT","发运身份占用竞争");
        }
    }
    /** 原来源回执去重后才调用；每个SN确认失败须回滚整次T3。 */
    public void shipmentPosted(String e,String w,String command,SerialExecutionSelection selection) {
        var mapper=session.getMapper(OutboundSerialMapper.class);
        for(var identity:selection.identities())
            if(mapper.shipmentPosted(e,w,command,identity.serialId(),identity.ownerEpoch().longValue(),Timestamp.from(clock.instant()))!=1)
                throw new OutboundException("SERIAL_SHIP_CONFLICT","原发运身份回执缺少匹配占用");
    }
}
