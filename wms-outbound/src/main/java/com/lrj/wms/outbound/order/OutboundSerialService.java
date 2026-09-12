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
}
