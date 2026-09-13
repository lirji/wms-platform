package com.lrj.wms.inventory.serial;

import com.lrj.wms.contract.messaging.SerialTransferCommand;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper;
import com.lrj.wms.runtime.messaging.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.*;

/** 库存命令先落原效果，完成回执等待独立登记事实；任何网络调用均由已有恢复执行器处理。 */
public final class SerialTransferCommandService {
    private final SqlSession session;
    private final Clock clock;
    public SerialTransferCommandService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** Inbox与本地效果使用同一事务；重复只核对原摘要，不重新判断已变化的当前身份。 */
    public void accept(RuntimeMessage message) {
        if(!"wms-fulfillment".equals(message.sourceService()) || !SerialTransferCommand.EVENT.equals(message.eventType()))
            throw new MessageRejectedException("UNSUPPORTED_TRANSFER_COMMAND");
        SerialTransferCommand command;
        try {command=RuntimeMessage.JSON.treeToValue(message.payload(),SerialTransferCommand.class);}
        catch(RuntimeException invalid) {throw new MessageRejectedException("INVALID_TRANSFER_COMMAND");}
        if(!message.warehouseId().equals(command.warehouseId()) || !message.aggregateId().equals(command.commandId()))
            throw new MessageRejectedException("TRANSFER_SCOPE_MISMATCH");
        String e=message.enterpriseId(),w=message.warehouseId();
        SerialRecoveryService.requireWritable(session,e,w);
        var mapper=session.getMapper(SerialTransferCommandMapper.class);
        String payload=RuntimeMessage.JSON.writeValueAsString(command),hash=RuntimeMessage.contentHash(payload);
        var original=mapper.lock(e,w,command.commandId());
        if(original!=null) {
            if(!hash.equals(original.get("context_hash"))) throw new MessageRejectedException("TRANSFER_COMMAND_CONFLICT");
            return;
        }
        var context=command.postingContext();var master=session.getMapper(MasterdataHttpMapper.class);
        var sku=master.getSku(e,context.skuId());var location=master.getLocation(e,w,context.sourceLocationId());
        if(!active(sku) || !active(master.getWarehouse(e,w)) || !active(location)
                || !flag(sku.get("serial_enabled")) || !context.baseUnit().equals(sku.get("base_unit")))
            throw new MessageRejectedException("TRANSFER_MASTERDATA_MISMATCH");
        boolean lotEnabled=flag(sku.get("lot_enabled"));
        if(lotEnabled=="NO_LOT".equals(context.lotId())) throw new MessageRejectedException("LOT_POLICY_MISMATCH");
        if(!lotEnabled && !"NO_LOT".equals(command.businessLotKey())) throw new MessageRejectedException("LOT_POLICY_MISMATCH");
        if(lotEnabled) {
            var lot=master.getLot(e,w,context.lotId());
            if(lot==null || !context.ownerId().equals(lot.get("owner_id")) || !context.skuId().equals(lot.get("sku_id")) || !command.businessLotKey().equals(lot.get("business_lot_key")))
                throw new MessageRejectedException("LOT_SCOPE_MISMATCH");
        }
        mapper.insert(e,w,command.commandId(),hash,payload,now());
        original=mapper.lock(e,w,command.commandId());
        if(original==null || !hash.equals(original.get("context_hash"))) throw new MessageRejectedException("TRANSFER_COMMAND_CONFLICT");
        var service=new SerialTransferLocalService(session,clock,null);
        var bucket=StockBucketKey.of(e,w,context.ownerId(),context.sourceLocationId(),context.skuId(),context.lotId(),context.qualityCode());
        for(var identity:command.selection().identities()) {
            String ref=reference(e,command.commandId(),identity.serialId());
            if("ISSUE".equals(command.action())) {
                // 先观察维度，封闭服务按门禁→身份→余额加锁；返回后再次核对原桶，不颠倒既有锁顺序。
                var source=session.getMapper(SerialReleaseMapper.class).location(e,w,identity.serialId());
                if(source==null || !context.ownerId().equals(source.get("owner_id")) || !context.skuId().equals(source.get("sku_id"))
                        || !context.lotId().equals(source.get("lot_id")) || !context.qualityCode().equals(source.get("quality_code"))
                        || !context.sourceLocationId().equals(source.get("location_id"))) throw new MessageRejectedException("TRANSFER_SOURCE_BUCKET_MISMATCH");
                var sealed=service.sealSource(e,w,identity.serialId(),command.transferId(),identity.ownerEpoch().longValue(),ref,command.targetWarehouseId());
                if(!source.get("balance_id").equals(sealed.get("balanceId"))) throw new InventoryException("VERSION_CONFLICT","源身份已移位");
            } else service.stageDestination(e,w,ref,command.transferId(),command.actorId(),identity.serialId(),bucket,command.transferId(),identity.ownerEpoch().longValue());
        }
    }

    /** 每仓最多20个命令，每个最多200身份；失败不越过登记证明，下一调度继续。 */
    public static int completeDue(SqlSessionFactory sessions,Clock clock,String e,String w) {
        int completed=0;
        for(int n=0;n<20;n++) try(var session=sessions.openSession(false)) {
            SerialRecoveryService.requireWritable(session,e,w);
            var mapper=session.getMapper(SerialTransferCommandMapper.class);Timestamp now=Timestamp.from(clock.instant());
            var row=mapper.next(e,w,now);if(row==null) {session.commit();break;}
            var command=RuntimeMessage.JSON.readValue(String.valueOf(row.get("payload")),SerialTransferCommand.class);
            boolean source="ISSUE".equals(command.action());
            var proofs=mapper.proofs(e,w,source,command.transferId(),command.selection().identities().stream().map(i->i.serialId()).toList());
            var bySerial=new HashMap<String,Map<String,Object>>();for(var proof:proofs) bySerial.put((String)proof.get("serial_id"),proof);
            boolean complete=command.selection().identities().stream().allMatch(identity->{
                var proof=bySerial.get(identity.serialId());
                return proof!=null && "DONE".equals(proof.get("state")) && command.postingContext().skuId().equals(proof.get("sku_id"))
                        && reference(e,command.commandId(),identity.serialId()).equals(proof.get("operation_id"))
                        && identity.ownerEpoch().longValue()==((Number)proof.get("from_epoch")).longValue()
                        && (!source || command.targetWarehouseId().equals(proof.get("target_warehouse_id")));
            });
            if(mapper.checked(e,w,command.commandId(),((Number)row.get("version")).longValue(),complete,Timestamp.from(clock.instant().plusSeconds(2)),now)!=1)
                throw new InventoryException("VERSION_CONFLICT","调拨完成检查点竞争");
            if(complete) {
                String event=RuntimeMessage.hash("TRANSFER_RESULT\n"+e+"\n"+command.commandId());
                String payload=RuntimeMessage.JSON.writeValueAsString(Map.of("schemaVersion",1,"commandId",command.commandId(),"contextHash",row.get("context_hash"),"state","COMPLETE"));
                session.getMapper(OutboxMapper.class).insertPending(event,e,w,"SERIAL_TRANSFER",command.commandId(),1,SerialTransferCommand.RESULT,event,payload,now);
                completed++;
            }
            session.commit();
        }
        return completed;
    }
    private static boolean active(Map<String,Object> row) {return row!=null && "ACTIVE".equals(row.get("state"));}
    private static boolean flag(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0;}
    /** 逐SN业务引用与调拨命令永久关联，不能因重启或丢回执改变。 */
    public static String reference(String e,String command,String serial) {return RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("SERIAL_TRANSFER",e,command,serial)));}
    private Timestamp now() {return Timestamp.from(clock.instant());}
}
