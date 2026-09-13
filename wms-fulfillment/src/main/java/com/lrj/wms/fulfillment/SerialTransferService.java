package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 公开序列调拨只固定原请求；收到库存原命令完成证明后才累计单据数量。 */
public final class SerialTransferService {
    private final SqlSession session;
    private final Clock clock;
    public SerialTransferService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 原操作键不允许换单、换SN、换桶；源待完成量占计划上限，目的SN与额度只能绑定一个命令。 */
    public Map<String,Object> request(String e,String transfer,String line,String operation,String action,StockPostingContext context,
            SerialExecutionSelection selection,BigDecimal quantity,String authorization,Long tokenVersion,String actor) {
        if(operation==null || operation.isBlank() || operation.length()>64 || context==null || selection==null) throw error("INVALID_TRANSFER_COMMAND");
        selection.requireQuantity(quantity);
        var transferMapper=session.getMapper(TransferMapper.class);var order=transferMapper.lockOrder(e,transfer);
        if(order==null) throw error("RESOURCE_NOT_FOUND");
        var originalLine=transferMapper.lockLine(e,transfer,line);if(originalLine==null || !context.skuId().equals(originalLine.get("sku_id"))) throw error("TRANSFER_LINE_MISMATCH");
        String source=(String)order.get("source_warehouse_id"),target=(String)order.get("target_warehouse_id"),w="ISSUE".equals(action)?source:target;
        String id=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("SERIAL_TRANSFER",e,w,action,operation)));
        var command=new SerialTransferCommand(1,id,action,transfer,line,source,target,(String)originalLine.get("business_lot_key"),context,selection,actor);
        String payload=RuntimeMessage.JSON.writeValueAsString(command),hash=RuntimeMessage.contentHash(payload);
        var mapper=session.getMapper(SerialTransferMapper.class);var old=mapper.lock(e,id);
        if(old!=null) {
            if(!hash.equals(old.get("context_hash")) || !Objects.equals(authorization,old.get("authorization_id"))
                    || !Objects.equals(tokenVersion,old.get("authorization_version"))) throw error("OPERATION_CONFLICT");
            return view(old);
        }
        int count=selection.identities().size();
        var serials=selection.identities().stream().map(i->i.serialId()).toList();
        if("ISSUE".equals(action)) {
            if(authorization!=null || tokenVersion!=null || !context.lotId().equals(originalLine.get("source_lot_id"))) throw error("TRANSFER_SOURCE_MISMATCH");
            if(!serial(originalLine) && mapper.enable(e,transfer,line)!=1) throw error("TRANSFER_MODE_CONFLICT");
            var pending=BigDecimal.valueOf(mapper.pendingIssue(e,transfer,line));
            if(decimal(originalLine,"issued_qty").add(pending).add(quantity).compareTo(decimal(originalLine,"planned_qty"))>0) throw error("OVER_ISSUE");
            if(!mapper.lockMembers(e,transfer,serials).isEmpty()) throw error("SERIAL_ALREADY_ASSIGNED");
        } else {
            if(!serial(originalLine) || authorization==null || tokenVersion==null || tokenVersion<0) throw error("INVALID_AUTHORIZATION");
            var auth=transferMapper.lockAuth(e,authorization);
            if(auth==null || !transfer.equals(auth.get("transfer_id")) || !line.equals(auth.get("transfer_line_id")) || !target.equals(auth.get("target_warehouse_id"))
                    || !"OPEN".equals(auth.get("state")) || quantity.compareTo(decimal(auth,"quantity"))!=0
                    || tokenVersion.longValue()!=((Number)auth.get("token_version")).longValue()) throw error("TOKEN_MISMATCH");
            if(mapper.authorizationCommand(e,authorization)!=null) throw error("AUTHORIZATION_ALREADY_ASSIGNED");
            var members=mapper.lockMembers(e,transfer,serials);
            if(members.size()!=count) throw error("TRANSFER_IDENTITY_MISMATCH");
            Map<String,Long> epochs=new HashMap<>();selection.identities().forEach(i->epochs.put(i.serialId(),i.ownerEpoch().longValue()));
            for(var member:members) {
                if(!line.equals(member.get("line_id")) || !"COMPLETE".equals(member.get("source_state")) || member.get("receipt_command_id")!=null
                        || epochs.get((String)member.get("serial_id")).longValue()!=((Number)member.get("owner_epoch")).longValue()) throw error("TRANSFER_IDENTITY_MISMATCH");
                var issued=RuntimeMessage.JSON.readValue(String.valueOf(member.get("source_payload")),SerialTransferCommand.class);
                if(!context.ownerId().equals(issued.postingContext().ownerId()) || !context.baseUnit().equals(issued.postingContext().baseUnit())) throw error("TRANSFER_OWNER_MISMATCH");
            }
            Object targetLot=originalLine.get("target_lot_id");
            if(targetLot!=null && !context.lotId().equals(targetLot)) throw error("LOT_MAPPING_CONFLICT");
            // 第一批目的命令就固定批次映射，不能等回执时再与另一批竞争。
            if(transferMapper.bindTargetLot(e,transfer,line,context.lotId(),now())!=1) throw error("LOT_MAPPING_CONFLICT");
        }
        var row=new HashMap<String,Object>();row.putAll(Map.of("id",id,"e",e,"w",w,"transfer",transfer,"line",line,"action",action,"quantity",count,"hash",hash,"payload",payload));
        row.put("authorization",authorization);row.put("tokenVersion",tokenVersion);row.put("now",now());mapper.insert(row);
        if("ISSUE".equals(action)) mapper.members(e,transfer,line,id,selection.identities(),now());
        else if(mapper.assign(e,transfer,id,serials)!=count) throw error("SERIAL_ALREADY_ASSIGNED");
        // 同库Outbox按事件类型解释聚合身份；此处聚合为命令，不引用或伪造分配attempt。
        if(session.getMapper(FulfillmentMapper.class).insertOutboxIgnore(id,e,id,w,SerialTransferCommand.EVENT,id,payload,now())!=1) throw error("VERSION_CONFLICT");
        return view(mapper.lock(e,id));
    }

    /** Inbox、原完成位、issued/received与额度消费同事务；迟到重复不会再累计。 */
    public void complete(RuntimeMessage message) {
        if(!"wms-inventory".equals(message.sourceService()) || !SerialTransferCommand.RESULT.equals(message.eventType())) throw new MessageRejectedException("UNSUPPORTED_TRANSFER_RESULT");
        var payload=message.payload();
        if(!payload.path("schemaVersion").isIntegralNumber() || !payload.path("schemaVersion").canConvertToInt() || payload.path("schemaVersion").intValue()!=1
                || !"COMPLETE".equals(payload.path("state").asString()) || !message.aggregateId().equals(payload.path("commandId").asString())) throw new MessageRejectedException("INVALID_TRANSFER_RESULT");
        String e=message.enterpriseId(),id=message.aggregateId();var mapper=session.getMapper(SerialTransferMapper.class);
        // 先读原归属，再锁总单和命令，保持与公开请求/额度取消同一锁顺序。
        var preview=mapper.get(e,id);if(preview==null) throw error("TRANSFER_COMMAND_PENDING");
        var command=RuntimeMessage.JSON.readValue(String.valueOf(preview.get("payload")),SerialTransferCommand.class);
        if(!message.warehouseId().equals(command.warehouseId()) || !preview.get("context_hash").equals(payload.path("contextHash").asString())) throw new MessageRejectedException("TRANSFER_RESULT_MISMATCH");
        session.getMapper(TransferMapper.class).lockOrder(e,command.transferId());
        preview=mapper.lock(e,id);
        if("COMPLETE".equals(preview.get("state"))) return;
        var service=new TransferService(session,clock);BigDecimal quantity=BigDecimal.valueOf(command.selection().identities().size());
        if("ISSUE".equals(command.action())) service.applyFact(e,command.transferId(),command.lineId(),id,quantity,"ISSUE");
        else service.receiveVerified(e,command.transferId(),command.lineId(),id,(String)preview.get("authorization_id"),((Number)preview.get("authorization_version")).longValue(),quantity,command.postingContext().lotId());
        if(mapper.complete(e,id,now())!=1) throw error("VERSION_CONFLICT");
    }
    /** 查询返回原命令与处理中/完成，不以Outbox已发送冒充业务完成。 */
    public Map<String,Object> get(String e,String id) {var row=session.getMapper(SerialTransferMapper.class).lock(e,id);if(row==null) throw error("RESOURCE_NOT_FOUND");return view(row);}
    private static Map<String,Object> view(Map<String,Object> row) {
        return Map.of("commandId",row.get("id"),"state",row.get("state"),"warehouseId",row.get("warehouse_id"),"transferId",row.get("transfer_id"),"lineId",row.get("line_id"),
                "quantity",row.get("quantity"),"command",RuntimeMessage.JSON.readTree(String.valueOf(row.get("payload"))));
    }
    static boolean serial(Map<String,Object> line) {Object value=line.get("serial_execution");return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()==1;}
    private static BigDecimal decimal(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    private static TransferException error(String code) {return new TransferException(code,"调拨原命令、身份集合、额度或当前状态不允许此操作");}
    private Timestamp now() {return Timestamp.from(clock.instant());}
}
