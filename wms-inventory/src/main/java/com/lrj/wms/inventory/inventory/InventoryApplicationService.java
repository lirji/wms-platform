package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.InventoryPolicy;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 收货入账、预占、TCC Cancel 释放。先锁门禁再按稳定桶键锁余额，版本与影响行数必须核对。
 * Outbox 表在 S2-04；本切片与余额/流水同事务，不写虚假过账成功。
 */
public final class InventoryApplicationService {
    private final SqlSession session;
    private final Clock clock;

    public InventoryApplicationService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 收货增加实物。同 operationId 重试返回原结果，不二次加量。 */
    public String receive(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            StockBucketKey bucket, Quantity qty) {
        requireSameScope(enterpriseId, warehouseId, bucket);
        requirePositive(qty);
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return operationId;
        }
        Timestamp now = now();
        requireGate(mapper, enterpriseId, warehouseId, bucket.locationId(), InventoryCodes.CMD_NORMAL_MUTATION);
        Map<String, Object> balance = ensureBalance(mapper, bucket, now);
        BigDecimal delta = qty.toBigDecimal();
        int updated = mapper.casAdjust(enterpriseId, warehouseId, String.valueOf(balance.get("id")), delta, BigDecimal.ZERO,
                BigDecimal.ZERO, longValue(balance.get("version")), now);
        if (updated != 1) {
            throw new InventoryException("VERSION_CONFLICT", "收货版本冲突");
        }
        Map<String, Object> after = mapper.lockBalanceById(enterpriseId, warehouseId, String.valueOf(balance.get("id")));
        mapper.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, operationId, 1,
                String.valueOf(after.get("id")), delta, BigDecimal.ZERO, BigDecimal.ZERO, decimal(after, "on_hand_qty"),
                decimal(after, "reserved_qty"), decimal(after, "free_execution_claim_qty"), longValue(after.get("version")),
                InventoryCodes.REASON_RECEIVE, documentId, actorId, now, now);
        return operationId;
    }

    /** Try 预占：GOOD 桶增加 reserved，写 TRIED 预占头/行。 */
    public String reserve(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            String allocationId, String attemptId, String xid, long branchId, String actionName, long routeEpoch,
            String requestDigest, StockBucketKey bucket, Quantity qty, String orderLineId) {
        requireSameScope(enterpriseId, warehouseId, bucket);
        requirePositive(qty);
        InventoryCodes.requireQuality(bucket.qualityCode());
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return operationId;
        }
        Timestamp now = now();
        requireGate(mapper, enterpriseId, warehouseId, bucket.locationId(), InventoryCodes.CMD_NEW_RESERVE);
        Map<String, Object> balance = ensureBalance(mapper, bucket, now);
        int updated = mapper.casReserveGood(enterpriseId, warehouseId, String.valueOf(balance.get("id")), qty.toBigDecimal(),
                longValue(balance.get("version")), now);
        if (updated != 1) {
            throw new InventoryException("STOCK_INSUFFICIENT", "可分配量不足或非GOOD");
        }
        Map<String, Object> after = mapper.lockBalanceById(enterpriseId, warehouseId, String.valueOf(balance.get("id")));
        String reservationId = UUID.randomUUID().toString();
        mapper.insertReservation(reservationId, enterpriseId, warehouseId, allocationId, attemptId, requestDigest, 1,
                ReservationState.TRIED, xid, branchId, actionName, routeEpoch, null, now);
        mapper.insertReservationLine(UUID.randomUUID().toString(), enterpriseId, warehouseId, reservationId, null, orderLineId,
                String.valueOf(after.get("id")), qty.toBigDecimal(), qty.toBigDecimal(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, now);
        mapper.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, operationId, 1,
                String.valueOf(after.get("id")), BigDecimal.ZERO, qty.toBigDecimal(), BigDecimal.ZERO,
                decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"), decimal(after, "free_execution_claim_qty"),
                longValue(after.get("version")), InventoryCodes.REASON_RESERVE, documentId, actorId, now, now);
        return reservationId;
    }

    /** TCC Cancel：仅 TRIED 可释放 reserved。CONFIRMED 拒绝。 */
    public void cancelTried(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            String allocationId, String attemptId) {
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return;
        }
        Timestamp now = now();
        List<String> locationIds = mapper.reservationLocationIds(enterpriseId, warehouseId, allocationId, attemptId);
        if (locationIds.isEmpty()) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "预占不存在");
        }
        for (String locationId : locationIds) {
            requireGate(mapper, enterpriseId, warehouseId, locationId, InventoryCodes.CMD_TCC_CANCEL);
        }
        Map<String, Object> head = mapper.lockReservationByAttempt(enterpriseId, warehouseId, allocationId, attemptId);
        if (head == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "预占不存在");
        }
        ReservationState.requireTransition(String.valueOf(head.get("state")), ReservationState.CANCELLED,
                ReservationState.CAUSE_TCC_CANCEL);
        List<Map<String, Object>> lines = mapper.listReservationLines(enterpriseId, warehouseId, String.valueOf(head.get("id")));
        int entry = 1;
        for (Map<String, Object> line : lines) {
            BigDecimal remaining = decimal(line, "remaining_qty");
            if (mapper.casReleaseTriedLine(enterpriseId, warehouseId, String.valueOf(line.get("id")), now) != 1) {
                throw new InventoryException("VERSION_CONFLICT", "预占明细已变化");
            }
            Map<String, Object> balance = mapper.lockBalanceById(enterpriseId, warehouseId,
                    String.valueOf(line.get("balance_id")));
            int updated = mapper.casAdjust(enterpriseId, warehouseId, String.valueOf(balance.get("id")), BigDecimal.ZERO,
                    remaining.negate(), BigDecimal.ZERO, longValue(balance.get("version")), now);
            if (updated != 1) {
                throw new InventoryException("VERSION_CONFLICT", "释放预占版本冲突");
            }
            Map<String, Object> after = mapper.lockBalanceById(enterpriseId, warehouseId, String.valueOf(balance.get("id")));
            mapper.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, operationId, entry++,
                    String.valueOf(after.get("id")), BigDecimal.ZERO, remaining.negate(), BigDecimal.ZERO,
                    decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"),
                    decimal(after, "free_execution_claim_qty"), longValue(after.get("version")),
                    InventoryCodes.REASON_RELEASE, documentId, actorId, now, now);
        }
        if (mapper.casReservationState(enterpriseId, warehouseId, String.valueOf(head.get("id")), ReservationState.TRIED,
                ReservationState.CANCELLED, longValue(head.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "预占头状态冲突");
        }
    }

    private Map<String, Object> ensureBalance(InventoryMapper mapper, StockBucketKey bucket, Timestamp now) {
        mapper.insertBalance(UUID.randomUUID().toString(), bucket.enterpriseId(), bucket.warehouseId(), bucket.ownerId(),
                bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode(), now);
        Map<String, Object> locked = mapper.lockBalanceByDimension(bucket.enterpriseId(), bucket.warehouseId(),
                bucket.ownerId(), bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode());
        if (locked == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库存桶不存在");
        }
        return locked;
    }

    private void requireGate(InventoryMapper mapper, String enterpriseId, String warehouseId, String locationId,
            String command) {
        Map<String, Object> gate = mapper.lockGate(enterpriseId, warehouseId, locationId);
        if (gate == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库位门禁不存在");
        }
        String decision = InventoryPolicy.decideGate(String.valueOf(gate.get("state")), command);
        if (!InventoryCodes.DECISION_ALLOW.equals(decision)) {
            throw new InventoryException("STOCK_FROZEN", "门禁拒绝库存写入：" + decision);
        }
    }

    private static void requireSameScope(String enterpriseId, String warehouseId, StockBucketKey bucket) {
        MasterdataCodes.requireCode("企业标识", enterpriseId);
        MasterdataCodes.requireCode("仓库标识", warehouseId);
        if (!enterpriseId.equals(bucket.enterpriseId()) || !warehouseId.equals(bucket.warehouseId())) {
            throw new InventoryException("WAREHOUSE_FORBIDDEN", "桶键与请求仓范围不一致");
        }
    }

    private static void requirePositive(Quantity qty) {
        InventoryPolicy.requireNonNegative("qty", qty);
        if (!qty.isPositive()) {
            throw new InventoryException("INVALID_QUANTITY", "数量必须为正");
        }
    }

    private static BigDecimal decimal(Map<String, Object> row, String column) {
        return new BigDecimal(row.get(column).toString());
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private InventoryMapper mapper() {
        return session.getMapper(InventoryMapper.class);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
