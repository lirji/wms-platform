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

/** 数量转桶的同一事务内逐身份核对和绑定；不在数据库事务内访问登记服务。 */
public final class SerialQualityStockService {
    private final SqlSession session;
    private final Clock clock;
    public SerialQualityStockService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 调用时数量三桶已按稳定顺序锁定，身份仍指向旧质量桶；校验失败由整个用例回滚。 */
    public void apply(String e,String w,StockBucketKey hold,ReceiptQualityDecision decision,SerialQualityObservation observation,
            BigDecimal previousAccepted,BigDecimal previousRejected) {
        observation.requireDecision(decision);
        var batch=session.getMapper(SerialReceiptBatchMapper.class).lock(e,w,decision.receiptCommandId());
        if(batch==null || !"APPLIED".equals(batch.get("state"))) throw new InventoryException("SERIAL_BATCH_CONTEXT_REQUIRED","原序列收货批次未完整落地");
        var receipt=RuntimeMessage.JSON.readValue(String.valueOf(batch.get("observation_json")),SerialReceiptObservation.class);
        try {observation.requireReceipt(receipt);}
        catch(IllegalArgumentException invalid) {throw new InventoryException("SERIAL_BATCH_CONFLICT","质检身份不属于本批收货");}
        var inventory=session.getMapper(InventoryMapper.class);var locals=session.getMapper(LocalSerialMapper.class);
        record Change(String serial,String quality,Map<String,Object> local) { }
        var changes=new ArrayList<Change>();int good=0,rejected=0;
        for(String serial:receipt.serialIds()) {
            var local=locals.lock(e,w,serial);
            if(local==null || !decision.receiptCommandId().equals(local.get("receipt_operation_id")) || local.get("balance_id")==null
                    || !hold.skuId().equals(local.get("sku_id")) || !hold.lotId().equals(local.get("lot_id")))
                throw new InventoryException("SERIAL_BATCH_CONFLICT","本地身份不属于原收货批次");
            var balance=inventory.lockBalanceById(e,w,String.valueOf(local.get("balance_id")));
            if(balance==null || !hold.ownerId().equals(balance.get("owner_id")) || !hold.skuId().equals(balance.get("sku_id"))
                    || !hold.lotId().equals(balance.get("lot_id"))) throw new InventoryException("SERIAL_BUCKET_CONFLICT","序列号库存维度不一致");
            String current=String.valueOf(balance.get("quality_code")),desired=observation.quality(serial),state=String.valueOf(local.get("state"));
            if("GOOD".equals(current)) good++;else if("REJECTED".equals(current)) rejected++;
            else if(!"HOLD".equals(current)) throw new InventoryException("SERIAL_QUALITY_CONFLICT","原身份不是可质检质量桶");
            boolean authorized="AUTHORIZED".equals(state)&&"ACTIVE".equals(local.get("registry_state"));
            // 未选中且仍HOLD的身份可继续等待登记；任何质量放行或已放行身份必须有持久授权。
            if(!authorized && !("HOLD".equals(current)&&"HOLD".equals(desired)&&Set.of("HOLD_RECEIVED","EXCEPTION").contains(state)))
                throw new InventoryException("SERIAL_REGISTRY_PENDING","身份尚未获得有效登记授权，不能改变质量");
            boolean sameLocation=hold.locationId().equals(balance.get("location_id"));
            if(!sameLocation && !("GOOD".equals(current)&&"GOOD".equals(desired)))
                throw new InventoryException("QUALITY_ALREADY_MOVED","已移出收货位的身份不能重新判定质量");
            if(!current.equals(desired)) {
                if(decimal(balance.get("reserved_qty")).signum()>0 || decimal(balance.get("free_execution_claim_qty")).signum()>0)
                    throw new InventoryException("SERIAL_QUALITY_RESERVED","已被占用的质量桶不能通过等量身份交换绕过保护");
                changes.add(new Change(serial,desired,local));
            }
        }
        if(previousAccepted.compareTo(BigDecimal.valueOf(good))!=0 || previousRejected.compareTo(BigDecimal.valueOf(rejected))!=0)
            throw new InventoryException("SERIAL_QUALITY_DRIFT","原质量累计量与本批实际身份质量不一致");
        for(var change:changes) {
            var target=inventory.lockBalanceByDimension(e,w,hold.ownerId(),hold.locationId(),hold.skuId(),hold.lotId(),change.quality());
            if(target==null || locals.updateState(e,w,change.serial(),String.valueOf(target.get("id")),"AUTHORIZED","ACTIVE",null,
                    ((Number)change.local().get("owner_epoch")).longValue(),Timestamp.from(clock.instant()))!=1)
                throw new InventoryException("VERSION_CONFLICT","质量数量和身份转桶必须同事务完成");
        }
    }
    private static BigDecimal decimal(Object value) {return new BigDecimal(value.toString());}
}
