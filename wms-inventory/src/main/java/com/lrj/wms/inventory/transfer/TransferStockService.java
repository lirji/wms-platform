package com.lrj.wms.inventory.transfer;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.InventoryPolicy;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 非序列号调拨源仓扣量。与 fulfillment 分库分事务。不发明 OQ-03。
 */
public final class TransferStockService {
    public static final String REASON_ISSUE = "TRANSFER_ISSUE";

    private final SqlSession session;
    private final Clock clock;

    public TransferStockService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 源仓按操作键发出。同 operation 重放不二次扣减。 */
    public Map<String, Object> issue(String enterpriseId, String warehouseId, String operationId, String documentId,
            String actorId, StockBucketKey bucket, Quantity qty) {
        if (qty == null || qty.toBigDecimal().signum() <= 0) {
            throw new InventoryException("INVALID_QTY", "发出数量必须为正");
        }
        if (!enterpriseId.equals(bucket.enterpriseId()) || !warehouseId.equals(bucket.warehouseId())) {
            throw new InventoryException("SCOPE_MISMATCH", "发出范围与桶不一致");
        }
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> gate = inventory.lockGate(enterpriseId, warehouseId, bucket.locationId());
        if (gate == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库位门禁不存在");
        }
        String decision = InventoryPolicy.decideGate(String.valueOf(gate.get("state")),
                InventoryCodes.CMD_NORMAL_MUTATION);
        if (!InventoryCodes.DECISION_ALLOW.equals(decision)) {
            throw new InventoryException("STOCK_FROZEN", "门禁拒绝调拨发出：" + decision);
        }
        if (inventory.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return replay(inventory.lockBalanceByDimension(enterpriseId, warehouseId, bucket.ownerId(),
                    bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode()), true);
        }
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> balance = inventory.lockBalanceByDimension(enterpriseId, warehouseId, bucket.ownerId(),
                bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode());
        if (balance == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "源仓余额不存在");
        }
        if (inventory.casAdjust(enterpriseId, warehouseId, String.valueOf(balance.get("id")),
                qty.toBigDecimal().negate(), BigDecimal.ZERO, BigDecimal.ZERO, asLong(balance.get("version")),
                now) != 1) {
            throw new InventoryException("STOCK_INSUFFICIENT", "源仓可发量不足");
        }
        Map<String, Object> after = inventory.lockBalanceByDimension(enterpriseId, warehouseId, bucket.ownerId(),
                bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode());
        inventory.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, operationId, 1,
                String.valueOf(after.get("id")), qty.toBigDecimal().negate(), BigDecimal.ZERO, BigDecimal.ZERO,
                decimal(after.get("on_hand_qty")), decimal(after.get("reserved_qty")),
                decimal(after.get("free_execution_claim_qty")), asLong(after.get("version")), REASON_ISSUE, documentId,
                actorId, now, now);
        return replay(after, false);
    }

    public Map<String, Object> receive(String enterpriseId, String warehouseId, String operationId, String documentId,
            String actorId, StockBucketKey bucket, Quantity qty) {
        new InventoryApplicationService(session, clock).receive(enterpriseId, warehouseId, operationId, documentId,
                actorId, bucket, qty);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        return replay(inventory.lockBalanceByDimension(enterpriseId, warehouseId, bucket.ownerId(), bucket.locationId(),
                bucket.skuId(), bucket.lotId(), bucket.qualityCode()), false);
    }

    private static Map<String, Object> replay(Map<String, Object> balance, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("balanceId", balance == null ? null : balance.get("id"));
        body.put("onHandQty", balance == null ? BigDecimal.ZERO : balance.get("on_hand_qty"));
        body.put("replayed", replayed);
        return body;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal qty) {
            return qty;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
