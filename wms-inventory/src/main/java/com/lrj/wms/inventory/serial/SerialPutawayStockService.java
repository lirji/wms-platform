package com.lrj.wms.inventory.serial;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import org.apache.ibatis.session.SqlSession;

/** 数量上架与具体身份绑定使用同一库存事务，拒绝用别的批次或别的位置补足所选身份。 */
public final class SerialPutawayStockService {
    private final SqlSession session;
    private final Clock clock;
    public SerialPutawayStockService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 调用方已按稳定顺序锁定并移动数量；任一身份失败均回滚本次完整数量动作。 */
    public void move(String e,String w,String receipt,StockBucketKey source,StockBucketKey target,SerialStockSelection selection) {
        var batch=session.getMapper(SerialReceiptBatchMapper.class).lock(e,w,receipt);
        if(batch==null || !"APPLIED".equals(batch.get("state"))) throw new InventoryException("SERIAL_BATCH_CONTEXT_REQUIRED","原序列收货批次不存在");
        var original=RuntimeMessage.JSON.readValue(String.valueOf(batch.get("observation_json")),SerialReceiptObservation.class);
        if(!original.serialIds().containsAll(selection.serialIds())) throw new InventoryException("SERIAL_BATCH_CONFLICT","所选身份不属于原批次");
        var inventory=session.getMapper(InventoryMapper.class);
        var from=inventory.lockBalanceByDimension(e,w,source.ownerId(),source.locationId(),source.skuId(),source.lotId(),source.qualityCode());
        var to=inventory.lockBalanceByDimension(e,w,target.ownerId(),target.locationId(),target.skuId(),target.lotId(),target.qualityCode());
        if(from==null || to==null || !"GOOD".equals(source.qualityCode()) || !"GOOD".equals(target.qualityCode()))
            throw new InventoryException("SERIAL_BUCKET_CONFLICT","上架必须在明确的合格源目标桶之间移动");
        if(new BigDecimal(from.get("reserved_qty").toString()).signum()>0 || new BigDecimal(from.get("free_execution_claim_qty").toString()).signum()>0)
            throw new InventoryException("SERIAL_PUTAWAY_RESERVED","不能从已被占用的桶猜测哪些身份仍可自由上架");
        var locals=session.getMapper(LocalSerialMapper.class);
        for(String serial:selection.serialIds()) {
            var local=locals.lock(e,w,serial);
            if(local==null || !receipt.equals(local.get("receipt_operation_id")) || !source.skuId().equals(local.get("sku_id"))
                    || !source.lotId().equals(local.get("lot_id")) || !from.get("id").equals(local.get("balance_id")) || local.get("transfer_id")!=null)
                throw new InventoryException("SERIAL_SELECTION_CONFLICT","所选身份已移出、属于其他批次或已进入转移");
            if(!"AUTHORIZED".equals(local.get("state")) || !"ACTIVE".equals(local.get("registry_state")))
                throw new InventoryException("SERIAL_REGISTRY_PENDING","所选身份尚未取得登记授权");
            if(locals.updateState(e,w,serial,String.valueOf(to.get("id")),"AUTHORIZED","ACTIVE",null,
                    ((Number)local.get("owner_epoch")).longValue(),Timestamp.from(clock.instant()))!=1)
                throw new InventoryException("VERSION_CONFLICT","身份上架与数量必须一起提交");
        }
    }
}
