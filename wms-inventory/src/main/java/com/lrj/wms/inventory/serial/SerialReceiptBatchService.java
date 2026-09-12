package com.lrj.wms.inventory.serial;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.inventory.inventory.*;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 一次收货的数量只过账一次；完整身份、登记意图和命令在同一库存事务内原子落地。 */
public final class SerialReceiptBatchService {
    private final SqlSession session;
    private final Clock clock;
    public SerialReceiptBatchService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 重放只验证原批次；身份已被后续合法操作移动后不能按旧收货再绑定回HOLD。 */
    public Map<String,Object> receive(String e,String w,String command,String parent,String part,String line,
            String actor,String execution,StockPostingContext context,Quantity qty,String previous,
            SerialReceiptObservation observation) {
        observation.requireQuantity(qty.toBigDecimal());
        context.requireForAction("RECEIVE");
        SerialRecoveryService.requireWritable(session,e,w);
        var batches=session.getMapper(SerialReceiptBatchMapper.class);
        String hash=RuntimeMessage.contentHash(RuntimeMessage.JSON.writeValueAsString(List.of(context,observation)));
        var batch=batches.lock(e,w,command);
        if(batch==null) {
            // 历史已有命令没有可信身份清单，禁止把本次输入伪装成当时的观察。
            if(session.getMapper(StockCommandMapper.class).lockByCommand(e,w,"wms-inbound",command)!=null)
                throw new InventoryException("SERIAL_BATCH_CONTEXT_REQUIRED","旧库存命令缺少原收货身份事实");
            batches.insert(Map.of("id",UUID.randomUUID().toString(),"e",e,"w",w,"command",command,"hash",hash,"observation",RuntimeMessage.JSON.writeValueAsString(observation),
                    "count",observation.serialIds().size(),"now",Timestamp.from(clock.instant())));
            batch=batches.lock(e,w,command);
        }
        if(batch==null || !hash.equals(batch.get("context_hash")))
            throw new InventoryException("SERIAL_BATCH_CONFLICT","同收货命令不能替换、追加或遗漏身份清单");
        var bucket=StockBucketKey.of(e,w,context.ownerId(),context.sourceLocationId(),context.skuId(),context.lotId(),"HOLD");
        var result=new StockCommandService(session,clock).applyReceive(e,w,"wms-inbound",command,parent,part,line,
                context.documentId(),actor,execution,bucket,qty,previous);
        if(!command.equals(result.get("commandId"))) throw new InventoryException("SOURCE_COMMAND_IDENTITY_MISMATCH","同事实须重放原命令");
        String state=String.valueOf(result.get("state"));
        if(!"PENDING".equals(batch.get("state"))) {
            if(!state.equals(batch.get("state"))) throw new InventoryException("SERIAL_BATCH_STATE_CONFLICT","身份批次与命令终态不一致");
            return result;
        }
        if("APPLIED".equals(state)) {
            var receipts=new SerialReceiptService(session,clock,null);
            for(String serial:observation.serialIds()) receipts.stagePostedIdentity(e,w,command,serial,bucket);
        }
        if(batches.finish(e,w,command,state,Timestamp.from(clock.instant()))!=1)
            throw new InventoryException("VERSION_CONFLICT","批次与身份必须一起完成");
        return result;
    }
}
