package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.InventoryPolicy;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.domain.CommandDigest;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 收货、预占、TCC Cancel、同仓移库与发运。先锁门禁再按稳定桶键锁余额。
 * 流水、Outbox 与 command_dedup 同会话提交。领取/发布由 OutboxPublisher 按物理库扫描。
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
        if (replayCommand(enterpriseId, warehouseId, InventoryCodes.REASON_RECEIVE, operationId,
                CommandDigest.v1(InventoryCodes.REASON_RECEIVE, documentId, bucket, qty.toPlainString()))) {
            return operationId;
        }
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
        recordLedgerAndOutbox(mapper, enterpriseId, warehouseId, operationId, 1, String.valueOf(after.get("id")),
                delta, BigDecimal.ZERO, decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"),
                decimal(after, "free_execution_claim_qty"), longValue(after.get("version")),
                InventoryCodes.REASON_RECEIVE, documentId, actorId, now);
        return operationId;
    }

    /** Try 预占：GOOD 桶增加 reserved，写 TRIED 预占头/行。 */
    public String reserve(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            String allocationId, String attemptId, String xid, long branchId, String actionName, long routeEpoch,
            String requestDigest, StockBucketKey bucket, Quantity qty, String orderLineId) {
        requireSameScope(enterpriseId, warehouseId, bucket);
        requirePositive(qty);
        InventoryCodes.requireQuality(bucket.qualityCode());
        if (replayCommand(enterpriseId, warehouseId, InventoryCodes.REASON_RESERVE, operationId,
                CommandDigest.v1(InventoryCodes.REASON_RESERVE, documentId, bucket, qty.toPlainString(), allocationId,
                        attemptId, orderLineId))) {
            return operationId;
        }
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return operationId;
        }
        Timestamp now = now();
        requireGate(mapper, enterpriseId, warehouseId, bucket.locationId(), InventoryCodes.CMD_NEW_RESERVE);
        Map<String, Object> after = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            Map<String, Object> balance = ensureBalance(mapper, bucket, now);
            int updated = mapper.casReserveGood(enterpriseId, warehouseId, String.valueOf(balance.get("id")),
                    qty.toBigDecimal(), longValue(balance.get("version")), now);
            if (updated == 1) {
                after = mapper.lockBalanceById(enterpriseId, warehouseId, String.valueOf(balance.get("id")));
                break;
            }
            Map<String, Object> latest = mapper.lockBalanceById(enterpriseId, warehouseId, String.valueOf(balance.get("id")));
            BigDecimal available = decimal(latest, "on_hand_qty").subtract(decimal(latest, "reserved_qty"))
                    .subtract(decimal(latest, "free_execution_claim_qty"));
            if (available.compareTo(qty.toBigDecimal()) < 0) {
                throw new InventoryException("STOCK_INSUFFICIENT", "可分配量不足或非GOOD");
            }
        }
        if (after == null) {
            throw new InventoryException("VERSION_CONFLICT", "预占版本冲突");
        }
        String reservationId = UUID.randomUUID().toString();
        mapper.insertReservation(reservationId, enterpriseId, warehouseId, allocationId, attemptId, requestDigest, 1,
                ReservationState.TRIED, xid, branchId, actionName, routeEpoch, null, now);
        mapper.insertReservationLine(UUID.randomUUID().toString(), enterpriseId, warehouseId, reservationId, null, orderLineId,
                String.valueOf(after.get("id")), qty.toBigDecimal(), qty.toBigDecimal(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, now);
        recordLedgerAndOutbox(mapper, enterpriseId, warehouseId, operationId, 1, String.valueOf(after.get("id")),
                BigDecimal.ZERO, qty.toBigDecimal(), decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"),
                decimal(after, "free_execution_claim_qty"), longValue(after.get("version")),
                InventoryCodes.REASON_RESERVE, documentId, actorId, now);
        return reservationId;
    }

    /** TCC Cancel：仅 TRIED 可释放 reserved。CONFIRMED 拒绝。 */
    public void cancelTried(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            String allocationId, String attemptId) {
        if (replayCommand(enterpriseId, warehouseId, InventoryCodes.REASON_RELEASE, operationId,
                CommandDigest.v1Parts(InventoryCodes.REASON_RELEASE, documentId, allocationId, attemptId))) {
            return;
        }
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
            recordLedgerAndOutbox(mapper, enterpriseId, warehouseId, operationId, entry++, String.valueOf(after.get("id")),
                    BigDecimal.ZERO, remaining.negate(), decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"),
                    decimal(after, "free_execution_claim_qty"), longValue(after.get("version")),
                    InventoryCodes.REASON_RELEASE, documentId, actorId, now);
        }
        if (mapper.casReservationState(enterpriseId, warehouseId, String.valueOf(head.get("id")), ReservationState.TRIED,
                ReservationState.CANCELLED, longValue(head.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "预占头状态冲突");
        }
    }

    /**
     * 同仓移库。moveReserved 为拣货：on_hand 与 reserved 同量转到目标桶。
     * 跨仓调拨不是本原语。
     */
    public void move(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            StockBucketKey source, StockBucketKey target, Quantity qty, boolean moveReserved) {
        requireSameScope(enterpriseId, warehouseId, source);
        requireSameScope(enterpriseId, warehouseId, target);
        requirePositive(qty);
        if (source.equals(target)) {
            throw new InventoryException("INVALID_QUANTITY", "移库源与目标不能相同");
        }
        if (replayCommand(enterpriseId, warehouseId, InventoryCodes.REASON_MOVE_OUT, operationId,
                CommandDigest.v1(InventoryCodes.REASON_MOVE_OUT, documentId, source, qty.toPlainString(),
                        target.locationId(), target.skuId(), target.lotId(), target.qualityCode(),
                        Boolean.toString(moveReserved)))) {
            return;
        }
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return;
        }
        Timestamp now = now();
        List<String> gates = List.of(source.locationId(), target.locationId()).stream().distinct().sorted().toList();
        for (String locationId : gates) {
            requireGate(mapper, enterpriseId, warehouseId, locationId, InventoryCodes.CMD_NORMAL_MUTATION);
        }
        BigDecimal delta = qty.toBigDecimal();
        BigDecimal reservedDelta = moveReserved ? delta : BigDecimal.ZERO;
        Map<String, Object> sourceRow = null;
        Map<String, Object> targetRow = null;
        for (StockBucketKey key : StockBucketKey.lockOrder(List.of(source, target))) {
            Map<String, Object> locked = ensureBalance(mapper, key, now);
            if (key.equals(source)) {
                sourceRow = locked;
            } else {
                targetRow = locked;
            }
        }
        apply(mapper, enterpriseId, warehouseId, String.valueOf(sourceRow.get("id")), delta.negate(), reservedDelta.negate(),
                longValue(sourceRow.get("version")), now, "STOCK_INSUFFICIENT", "移出数量不足或占用冲突");
        apply(mapper, enterpriseId, warehouseId, String.valueOf(targetRow.get("id")), delta, reservedDelta,
                longValue(targetRow.get("version")), now, "VERSION_CONFLICT", "移入版本冲突");
        if (moveReserved) {
            mapper.rebindRemainingLines(enterpriseId, warehouseId, String.valueOf(sourceRow.get("id")),
                    String.valueOf(targetRow.get("id")), now);
        }
        writeLedger(mapper, enterpriseId, warehouseId, operationId, 1, source, InventoryCodes.REASON_MOVE_OUT, delta.negate(),
                reservedDelta.negate(), documentId, actorId, now);
        writeLedger(mapper, enterpriseId, warehouseId, operationId, 2, target, InventoryCodes.REASON_MOVE_IN, delta,
                reservedDelta, documentId, actorId, now);
    }

    /** 发运：实物与预占同量减少。permit/claim 在 S2-04a。 */
    public void ship(String enterpriseId, String warehouseId, String operationId, String documentId, String actorId,
            StockBucketKey bucket, Quantity qty) {
        requireSameScope(enterpriseId, warehouseId, bucket);
        requirePositive(qty);
        if (replayCommand(enterpriseId, warehouseId, InventoryCodes.REASON_SHIP, operationId,
                CommandDigest.v1(InventoryCodes.REASON_SHIP, documentId, bucket, qty.toPlainString()))) {
            return;
        }
        InventoryMapper mapper = mapper();
        if (mapper.countLedger(enterpriseId, warehouseId, operationId) > 0) {
            return;
        }
        Timestamp now = now();
        requireGate(mapper, enterpriseId, warehouseId, bucket.locationId(), InventoryCodes.CMD_NORMAL_MUTATION);
        Map<String, Object> balance = ensureBalance(mapper, bucket, now);
        BigDecimal delta = qty.toBigDecimal();
        apply(mapper, enterpriseId, warehouseId, String.valueOf(balance.get("id")), delta.negate(), delta.negate(),
                longValue(balance.get("version")), now, "STOCK_INSUFFICIENT", "发运数量不足或未预占");
        writeLedger(mapper, enterpriseId, warehouseId, operationId, 1, bucket, InventoryCodes.REASON_SHIP, delta.negate(),
                delta.negate(), documentId, actorId, now);
    }

    private void apply(InventoryMapper mapper, String enterpriseId, String warehouseId, String balanceId,
            BigDecimal onHandDelta, BigDecimal reservedDelta, long version, Timestamp now, String code, String message) {
        int updated = mapper.casAdjust(enterpriseId, warehouseId, balanceId, onHandDelta, reservedDelta, BigDecimal.ZERO,
                version, now);
        if (updated != 1) {
            throw new InventoryException(code, message);
        }
    }

    private void writeLedger(InventoryMapper mapper, String enterpriseId, String warehouseId, String operationId,
            int entryNo, StockBucketKey bucket, String reason, BigDecimal onHandDelta, BigDecimal reservedDelta,
            String documentId, String actorId, Timestamp now) {
        Map<String, Object> after = mapper.lockBalanceByDimension(enterpriseId, warehouseId, bucket.ownerId(),
                bucket.locationId(), bucket.skuId(), bucket.lotId(), bucket.qualityCode());
        recordLedgerAndOutbox(mapper, enterpriseId, warehouseId, operationId, entryNo, String.valueOf(after.get("id")),
                onHandDelta, reservedDelta, decimal(after, "on_hand_qty"), decimal(after, "reserved_qty"),
                decimal(after, "free_execution_claim_qty"), longValue(after.get("version")), reason, documentId, actorId,
                now);
    }

    private boolean replayCommand(String enterpriseId, String warehouseId, String action, String operationId,
            String digest) {
        Timestamp now = now();
        Timestamp retainUntil = Timestamp.from(clock.instant().plus(Duration.ofDays(7)));
        CommandDedupMapper dedup = session.getMapper(CommandDedupMapper.class);
        int inserted = dedup.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId,
                InventoryCodes.SOURCE_INVENTORY, action, operationId, operationId, digest, CommandDigest.VERSION_1,
                InventoryCodes.COMMAND_APPLIED, retainUntil, now);
        if (inserted == 1) {
            return false;
        }
        Map<String, Object> existing = dedup.lockByClient(enterpriseId, warehouseId, InventoryCodes.SOURCE_INVENTORY, action,
                operationId);
        if (existing == null) {
            throw new InventoryException("VERSION_CONFLICT", "命令受理竞争");
        }
        if (!digest.equals(String.valueOf(existing.get("request_digest")))
                || CommandDigest.VERSION_1 != ((Number) existing.get("digest_version")).intValue()) {
            throw new InventoryException("COMMAND_CONFLICT", "同键不同内容");
        }
        return true;
    }

    private void recordLedgerAndOutbox(InventoryMapper mapper, String enterpriseId, String warehouseId, String operationId,
            int entryNo, String balanceId, BigDecimal onHandDelta, BigDecimal reservedDelta, BigDecimal onHandAfter,
            BigDecimal reservedAfter, BigDecimal claimAfter, long balanceVersion, String reason, String documentId,
            String actorId, Timestamp now) {
        String ledgerId = UUID.randomUUID().toString();
        mapper.insertLedger(ledgerId, enterpriseId, warehouseId, operationId, entryNo, balanceId, onHandDelta, reservedDelta,
                BigDecimal.ZERO, onHandAfter, reservedAfter, claimAfter, balanceVersion, reason, documentId, actorId, now,
                now);
        String payload = "{\"onHandDelta\":\"" + onHandDelta.toPlainString() + "\",\"reservedDelta\":\""
                + reservedDelta.toPlainString() + "\",\"onHandAfter\":\"" + onHandAfter.toPlainString()
                + "\",\"reservedAfter\":\"" + reservedAfter.toPlainString() + "\",\"ledgerEntryId\":\"" + ledgerId + "\"}";
        session.getMapper(OutboxMapper.class).insertPending(UUID.randomUUID().toString(), enterpriseId, warehouseId,
                InventoryCodes.AGGREGATE_STOCK_BALANCE, balanceId, balanceVersion, InventoryCodes.EVENT_BALANCE_CHANGED,
                operationId, payload, now);
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
