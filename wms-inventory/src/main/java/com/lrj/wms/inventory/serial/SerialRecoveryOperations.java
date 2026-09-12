package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.runtime.command.CommandKeys;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 人工重排只解除已核查的隔离，保留原始操作和上下文；审计与入队原子提交。 */
public final class SerialRecoveryOperations {
    public static Map<String,Object> retry(SqlSession session,Clock clock,String e,String w,String intent,String command,String actor,long epoch,String reason) {
        SerialRecoveryService.requireWritable(session,e,w);
        command=CommandKeys.resolve(command,null);
        if(epoch<0 || reason==null || reason.isBlank() || reason.length()>500 || actor==null || actor.isBlank() || actor.length()>128)
            throw new InventoryException("INVALID_ARGUMENT","重试需要合法代际、主体与核查依据");
        String hash=SerialRegistryHttpClient.digest(RuntimeMessage.JSON.writeValueAsString(List.of(e,w,intent,actor,epoch,reason)));
        var mapper=session.getMapper(SerialRecoveryMapper.class);
        var previous=mapper.lockAudit(e,w,command);
        if(previous!=null) {
            if(!hash.equals(previous.get("request_hash"))) throw new InventoryException("IDEMPOTENCY_PAYLOAD_MISMATCH","同一重试命令参数不同");
            return Map.of("recoveryId",previous.get("id"),"intentId",intent,"status","RETRY_ACCEPTED","replayed",true);
        }
        String id=UUID.randomUUID().toString(); Timestamp now=Timestamp.from(clock.instant());
        // 先占审计唯一键；并发同键在本事务读取已提交记录后返回原结果。
        mapper.audit(Map.of("id",id,"e",e,"w",w,"command",command,"intent",intent,"actor",actor,"reason",reason,"epoch",epoch,"hash",hash,"now",now));
        var stored=mapper.lockAudit(e,w,command);
        if(!hash.equals(stored.get("request_hash"))) throw new InventoryException("IDEMPOTENCY_PAYLOAD_MISMATCH","同一重试命令参数不同");
        if(!id.equals(stored.get("id"))) return Map.of("recoveryId",stored.get("id"),"intentId",intent,"status","RETRY_ACCEPTED","replayed",true);
        var row=mapper.lock(e,w,intent);
        if(row==null) throw new InventoryException("RESOURCE_NOT_FOUND","恢复意图不存在");
        if(mapper.requeue(e,w,intent,epoch,now)!=1) throw new InventoryException("VERSION_CONFLICT","仅可重新排队当前代际的隔离意图");
        return Map.of("recoveryId",id,"intentId",intent,"status","RETRY_ACCEPTED","replayed",false);
    }
    private SerialRecoveryOperations() { }
}
