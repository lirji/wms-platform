package com.lrj.wms.inventory.serial;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 原预占数量与具体SN同事务拣入暂存位，任一身份失败必须回滚整个PICK。 */
public final class SerialOutboundStockService {
    private final SqlSession session;
    private final Clock clock;
    public SerialOutboundStockService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 数量层已经锁定原分配/订单行及库位门，身份仍需核对原桶和实际代际。 */
    public void pick(String e,String w,String command,String operation,String orderLine,StockPostingContext context,
            StockBucketKey source,StockBucketKey target,SerialExecutionSelection selection) {
        var inventory=session.getMapper(InventoryMapper.class);
        var from=inventory.lockBalanceByDimension(e,w,source.ownerId(),source.locationId(),source.skuId(),source.lotId(),source.qualityCode());
        var to=inventory.lockBalanceByDimension(e,w,target.ownerId(),target.locationId(),target.skuId(),target.lotId(),target.qualityCode());
        if(from==null || to==null) throw new InventoryException("SERIAL_PICK_CONTEXT_REQUIRED","缺少原拣出或暂存桶");
        if(new BigDecimal(from.get("free_execution_claim_qty").toString()).signum()>0)
            throw new InventoryException("SERIAL_PICK_CONFLICT","设备自由领取尚无精确SN归属，不能猜测哪些身份可拣");
        var locals=session.getMapper(LocalSerialMapper.class);var facts=session.getMapper(SerialOutboundMapper.class);
        for(var identity:selection.identities()) {
            var local=locals.lock(e,w,identity.serialId());
            if(local==null || !from.get("id").equals(local.get("balance_id")) || !source.skuId().equals(local.get("sku_id"))
                    || !source.lotId().equals(local.get("lot_id")) || !"AUTHORIZED".equals(local.get("state")) || !"ACTIVE".equals(local.get("registry_state"))
                    || identity.ownerEpoch().longValue()!=((Number)local.get("owner_epoch")).longValue())
                throw new InventoryException("SERIAL_PICK_CONFLICT","所选SN不属于原授权桶或代际，不能借用其他身份");
            var row=new HashMap<String,Object>();
            row.putAll(Map.of("id",RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(e,w,command,identity.serialId()))),"e",e,"w",w,"command",command,"operation",operation,
                    "allocation",context.allocationId(),"attempt",context.allocationAttemptId(),"orderLine",orderLine,"sku",source.skuId(),"serial",identity.serialId()));
            row.putAll(Map.of("epoch",identity.ownerEpoch(),"source",from.get("id"),"target",to.get("id"),"now",Timestamp.from(clock.instant())));
            try {if(facts.insert(row)!=1) throw new InventoryException("SERIAL_PICK_CONFLICT","原拣货身份写入竞争");}
            catch(RuntimeException error) {if(com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(error)) throw new InventoryException("SERIAL_PICK_CONFLICT","该代际身份已被另一原拣货占用");throw error;}
            if(locals.updateState(e,w,identity.serialId(),to.get("id").toString(),"AUTHORIZED","ACTIVE",null,identity.ownerEpoch().longValue(),Timestamp.from(clock.instant()))!=1)
                throw new InventoryException("VERSION_CONFLICT","身份移位和预占数量必须同时提交");
        }
    }
}
