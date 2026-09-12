package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.InventoryPolicy;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.serial.LocalSerialMapper;
import com.lrj.wms.inventory.serial.SerialCountRegistryPort;
import com.lrj.wms.inventory.serial.SerialReceiptService;
import com.lrj.wms.inventory.serial.SerialRegistryConflictException;
import com.lrj.wms.inventory.serial.SerialRegistryUnavailableException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 盘点 QUIESCING/冻结/点数/审批/调整。未排空在途不得 FROZEN。
 * 盘亏导致 reserved&gt;新 on_hand 时行进入 RESERVATION_CONFLICT，计划保持冻结。
 * 序列号行必须提交观察身份集合；FOUND/MISSING 先登记再改本地。不发明 OQ-03。
 */
public final class CountService {
    public static final String DRAFT = "DRAFT";
    public static final String QUIESCING = "QUIESCING";
    public static final String FROZEN = "FROZEN";
    public static final String COUNTING = "COUNTING";
    public static final String REVIEWING = "REVIEWING";
    public static final String APPROVED = "APPROVED";
    public static final String APPLYING = "APPLYING";
    public static final String COMPLETED = "COMPLETED";
    public static final String LINE_SNAPSHOTTED = "SNAPSHOTTED";
    public static final String LINE_OBSERVED = "OBSERVED";
    public static final String LINE_APPLIED = "APPLIED";
    public static final String LINE_ZERO = "ZERO_DELTA";
    public static final String LINE_CONFLICT = "RESERVATION_CONFLICT";
    public static final String REASON_COUNT = "COUNT";
    public static final String PRESENT = "PRESENT";
    public static final String FOUND = "FOUND";
    public static final String MISSING = "MISSING";
    public static final String MISSING_PENDING = "MISSING_PENDING";

    private final SqlSession session;
    private final Clock clock;
    private final SerialCountRegistryPort registry;

    public CountService(SqlSession session, Clock clock) {
        this(session, clock, null);
    }

    public CountService(SqlSession session, Clock clock, SerialCountRegistryPort registry) {
        this.session = session;
        this.clock = clock;
        this.registry = registry;
    }

    public Map<String, Object> create(String enterpriseId, String warehouseId, String planId, String reason,
            List<String> locationIds) {
        requireId(planId, "INVALID_PLAN", "盘点计划不能为空");
        requireId(reason, "INVALID_REASON", "盘点原因不能为空");
        if (locationIds == null || locationIds.isEmpty()) {
            throw new InventoryException("INVALID_SCOPE", "盘点范围不能为空");
        }
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        if (counts.getPlan(enterpriseId, warehouseId, planId) != null) {
            return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
        }
        counts.insertPlan(planId, enterpriseId, warehouseId, DRAFT, reason, now);
        for (String locationId : locationIds) {
            requireId(locationId, "INVALID_LOCATION", "盘点库位不能为空");
            Map<String, Object> gate = inventory.lockGate(enterpriseId, warehouseId, locationId);
            if (gate == null) {
                throw new InventoryException("RESOURCE_NOT_FOUND", "库位门禁不存在");
            }
            counts.insertScope(UUID.randomUUID().toString(), enterpriseId, warehouseId, planId, locationId,
                    asLong(gate.get("fence_epoch")), now);
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    /** 范围库位进入 QUIESCING，拒绝新预占/普通写入。 */
    public Map<String, Object> startQuiescing(String enterpriseId, String warehouseId, String planId) {
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        if (QUIESCING.equals(String.valueOf(plan.get("status"))) || FROZEN.equals(String.valueOf(plan.get("status")))) {
            return view(enterpriseId, warehouseId, plan, counts);
        }
        if (!DRAFT.equals(String.valueOf(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "当前盘点状态不能进入排空");
        }
        for (Map<String, Object> scope : counts.listScope(enterpriseId, warehouseId, planId)) {
            String locationId = String.valueOf(scope.get("location_id"));
            inventory.lockGate(enterpriseId, warehouseId, locationId);
            if (counts.casGate(enterpriseId, warehouseId, locationId, MasterdataCodes.GATE_OPEN,
                    MasterdataCodes.GATE_QUIESCING, planId, REASON_COUNT, 0, now) != 1) {
                throw new InventoryException("GATE_CONFLICT", "库位门禁不能进入排空");
            }
        }
        if (counts.casPlanStatus(enterpriseId, warehouseId, planId, DRAFT, QUIESCING, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "盘点排空竞争");
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    /** 在途占用清零后冻结并生成不可覆盖快照。 */
    public Map<String, Object> freeze(String enterpriseId, String warehouseId, String planId) {
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        if (frozenOrLater(String.valueOf(plan.get("status")))) {
            return view(enterpriseId, warehouseId, plan, counts);
        }
        if (!QUIESCING.equals(String.valueOf(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "未排空不能冻结");
        }
        for (Map<String, Object> scope : counts.listScope(enterpriseId, warehouseId, planId)) {
            String locationId = String.valueOf(scope.get("location_id"));
            inventory.lockGate(enterpriseId, warehouseId, locationId);
            if (counts.countFreeClaims(enterpriseId, warehouseId, locationId) > 0
                    || counts.countInflightPermits(enterpriseId, warehouseId, locationId) > 0) {
                throw new InventoryException("COUNT_DRAIN_PENDING", "在途占用或未知执行未清零，不能冻结");
            }
            if (counts.casGate(enterpriseId, warehouseId, locationId, MasterdataCodes.GATE_QUIESCING,
                    MasterdataCodes.GATE_FROZEN, planId, REASON_COUNT, 1, now) != 1) {
                throw new InventoryException("GATE_CONFLICT", "库位门禁不能冻结");
            }
            Map<String, Object> frozenGate = inventory.lockGate(enterpriseId, warehouseId, locationId);
            counts.updateScopeEpoch(enterpriseId, warehouseId, planId, locationId, asLong(frozenGate.get("fence_epoch")),
                    now);
            for (Map<String, Object> balance : counts.listBalances(enterpriseId, warehouseId, locationId)) {
                counts.insertLine(UUID.randomUUID().toString(), enterpriseId, warehouseId, planId,
                        String.valueOf(balance.get("id")), locationId, asLong(balance.get("version")),
                        decimal(balance.get("on_hand_qty")), decimal(balance.get("reserved_qty")), LINE_SNAPSHOTTED, now);
            }
        }
        if (counts.casPlanStatus(enterpriseId, warehouseId, planId, QUIESCING, FROZEN, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "盘点冻结竞争");
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    /** 点数或复盘。同 observation 重放；新观察不覆盖旧扫描行。序列号行必须走 observeIdentities。 */
    public Map<String, Object> observe(String enterpriseId, String warehouseId, String planId, String lineId,
            String observationId, String qty, String actorId, int roundNo) {
        return persistObservation(enterpriseId, warehouseId, planId, lineId, observationId, qty, actorId, roundNo, true);
    }

    public Map<String, Object> submitReview(String enterpriseId, String warehouseId, String planId) {
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        if (REVIEWING.equals(String.valueOf(plan.get("status"))) || APPROVED.equals(String.valueOf(plan.get("status")))) {
            return view(enterpriseId, warehouseId, plan, counts);
        }
        if (!COUNTING.equals(String.valueOf(plan.get("status"))) && !FROZEN.equals(String.valueOf(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "当前盘点状态不能提交复盘");
        }
        for (Map<String, Object> line : counts.listLines(enterpriseId, warehouseId, planId)) {
            if (counts.countObservations(enterpriseId, warehouseId, String.valueOf(line.get("id"))) < 1) {
                throw new InventoryException("COUNT_UNOBSERVED", "存在尚未点数的快照行");
            }
        }
        if (counts.casPlanStatus(enterpriseId, warehouseId, planId, String.valueOf(plan.get("status")), REVIEWING,
                now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "提交复盘竞争");
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    public Map<String, Object> approve(String enterpriseId, String warehouseId, String planId, String approvalId,
            String actorId) {
        requireId(approvalId, "INVALID_APPROVAL", "审批标识不能为空");
        requireId(actorId, "INVALID_ACTOR", "审批人不能为空");
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        if (APPROVED.equals(String.valueOf(plan.get("status"))) || APPLYING.equals(String.valueOf(plan.get("status")))) {
            if (approvalId.equals(String.valueOf(plan.get("approval_id")))) {
                return view(enterpriseId, warehouseId, plan, counts);
            }
            throw new InventoryException("COUNT_STATE_CONFLICT", "盘点已由其他审批占用");
        }
        if (!REVIEWING.equals(String.valueOf(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "未复盘不能审批");
        }
        if (counts.casApprove(enterpriseId, warehouseId, planId, approvalId, actorId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "盘点审批竞争");
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    /**
     * 按行调整。盘亏不足覆盖预占则 RESERVATION_CONFLICT，已提交行不回滚。
     */
    public Map<String, Object> applyLine(String enterpriseId, String warehouseId, String planId, String lineId,
            String operationId, String actorId) {
        requireId(operationId, "INVALID_OPERATION", "调整操作不能为空");
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        String planStatus = String.valueOf(plan.get("status"));
        if (!APPROVED.equals(planStatus) && !APPLYING.equals(planStatus)) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "未审批不能调整");
        }
        Map<String, Object> line = counts.lockLine(enterpriseId, warehouseId, planId, lineId);
        if (line == null) {
            throw new InventoryException("COUNT_LINE_NOT_FOUND", "没有该盘点快照行");
        }
        if (LINE_APPLIED.equals(String.valueOf(line.get("status"))) || LINE_ZERO.equals(String.valueOf(line.get("status")))) {
            return lineView(line);
        }
        requireCountGate(inventory, counts, enterpriseId, warehouseId, planId, String.valueOf(line.get("location_id")),
                InventoryCodes.CMD_COUNT_ADJUST);
        BigDecimal counted = decimal(line.get("counted_qty"));
        Map<String, Object> balance = inventory.lockBalanceById(enterpriseId, warehouseId,
                String.valueOf(line.get("balance_id")));
        if (balance == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "快照桶不存在");
        }
        boolean hasObservationSerials = counts.countLineSerials(enterpriseId, warehouseId, lineId) > 0;
        boolean hasLocalSerials = !session.getMapper(LocalSerialMapper.class)
                .lockActiveByBalance(enterpriseId, warehouseId, String.valueOf(balance.get("id"))).isEmpty();
        if (hasLocalSerials && !hasObservationSerials) {
            throw new InventoryException("SERIAL_SET_REQUIRED", "序列号盘点不能只录数量");
        }
        BigDecimal onHand = decimal(balance.get("on_hand_qty"));
        BigDecimal reserved = decimal(balance.get("reserved_qty"));
        BigDecimal claim = decimal(balance.get("free_execution_claim_qty"));
        BigDecimal delta = counted.subtract(onHand);
        if (counted.compareTo(reserved.add(claim)) < 0) {
            counts.casLineStatus(enterpriseId, warehouseId, lineId, String.valueOf(line.get("status")), LINE_CONFLICT, now);
            throw new InventoryException("RESERVATION_CONFLICT", "盘亏不足覆盖预占，需先重分配或取消");
        }
        if (hasObservationSerials) {
            applySerialIdentities(enterpriseId, warehouseId, planId, line, operationId, now);
        }
        if (APPROVED.equals(planStatus)) {
            counts.casPlanStatus(enterpriseId, warehouseId, planId, APPROVED, APPLYING, now);
        }
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            counts.casLineStatus(enterpriseId, warehouseId, lineId, String.valueOf(line.get("status")), LINE_ZERO, now);
            return lineView(counts.lockLine(enterpriseId, warehouseId, planId, lineId));
        }
        if (inventory.casAdjust(enterpriseId, warehouseId, String.valueOf(balance.get("id")), delta, BigDecimal.ZERO,
                BigDecimal.ZERO, asLong(balance.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "盘点调整余额竞争");
        }
        Map<String, Object> after = inventory.lockBalanceById(enterpriseId, warehouseId,
                String.valueOf(line.get("balance_id")));
        inventory.insertLedger(UUID.randomUUID().toString(), enterpriseId, warehouseId, operationId, 1,
                String.valueOf(after.get("id")), delta, BigDecimal.ZERO, BigDecimal.ZERO, decimal(after.get("on_hand_qty")),
                decimal(after.get("reserved_qty")), decimal(after.get("free_execution_claim_qty")),
                asLong(after.get("version")), "COUNT_ADJUST", planId, actorId, now, now);
        counts.casLineStatus(enterpriseId, warehouseId, lineId, String.valueOf(line.get("status")), LINE_APPLIED, now);
        return lineView(counts.lockLine(enterpriseId, warehouseId, planId, lineId));
    }

    /** 全部调整终态后 CAS 解冻。部分失败保持 FROZEN/APPLYING。 */
    public Map<String, Object> unfreeze(String enterpriseId, String warehouseId, String planId) {
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        if (COMPLETED.equals(String.valueOf(plan.get("status")))) {
            return view(enterpriseId, warehouseId, plan, counts);
        }
        if (!APPLYING.equals(String.valueOf(plan.get("status"))) && !APPROVED.equals(String.valueOf(plan.get("status")))) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "调整未完成不能解冻");
        }
        for (Map<String, Object> line : counts.listLines(enterpriseId, warehouseId, planId)) {
            String status = String.valueOf(line.get("status"));
            if (!LINE_APPLIED.equals(status) && !LINE_ZERO.equals(status)) {
                throw new InventoryException("COUNT_APPLY_PENDING", "仍有未完成或冲突的调整行，保持冻结");
            }
        }
        if (session.getMapper(LocalSerialMapper.class).countMissingPending(enterpriseId, warehouseId, planId) > 0) {
            throw new InventoryException("COUNT_REGISTRY_PENDING", "登记尚未收敛失踪序列号，保持冻结");
        }
        for (Map<String, Object> scope : counts.listScope(enterpriseId, warehouseId, planId)) {
            String locationId = String.valueOf(scope.get("location_id"));
            inventory.lockGate(enterpriseId, warehouseId, locationId);
            if (counts.casUnfreeze(enterpriseId, warehouseId, locationId, planId, now) != 1) {
                throw new InventoryException("GATE_CONFLICT", "库位门禁不能解冻");
            }
        }
        if (counts.casPlanStatus(enterpriseId, warehouseId, planId, String.valueOf(plan.get("status")), COMPLETED,
                now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "盘点解冻竞争");
        }
        return view(enterpriseId, warehouseId, counts.lockPlan(enterpriseId, warehouseId, planId), counts);
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String planId) {
        CountMapper counts = session.getMapper(CountMapper.class);
        Map<String, Object> plan = counts.getPlan(enterpriseId, warehouseId, planId);
        if (plan == null) {
            throw new InventoryException("COUNT_NOT_FOUND", "没有该盘点计划");
        }
        return view(enterpriseId, warehouseId, plan, counts);
    }

    /**
     * 序列号点数。qty 必须等于见到的身份数，且等于快照 + FOUND - MISSING。
     * 禁止只录数量。
     */
    public Map<String, Object> observeIdentities(String enterpriseId, String warehouseId, String planId, String lineId,
            String observationId, String qty, String actorId, int roundNo, List<String> seenSerials) {
        if (seenSerials == null || seenSerials.isEmpty()) {
            throw new InventoryException("SERIAL_SET_REQUIRED", "序列号盘点必须提交观察身份集合");
        }
        BigDecimal counted = parseQty(qty);
        if (counted.compareTo(BigDecimal.valueOf(seenSerials.size())) != 0) {
            throw new InventoryException("SERIAL_QTY_MISMATCH", "点数必须等于观察身份数");
        }
        Map<String, Object> observation = persistObservation(enterpriseId, warehouseId, planId, lineId, observationId,
                qty, actorId, roundNo, false);
        CountMapper counts = session.getMapper(CountMapper.class);
        Map<String, Object> line = counts.lockLine(enterpriseId, warehouseId, planId, lineId);
        List<Map<String, Object>> locals = session.getMapper(LocalSerialMapper.class).lockActiveByBalance(enterpriseId,
                warehouseId, String.valueOf(line.get("balance_id")));
        java.util.Set<String> localIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> local : locals) {
            localIds.add(String.valueOf(local.get("serial_id")));
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Timestamp now = Timestamp.from(clock.instant());
        for (String raw : seenSerials) {
            String serial = SerialReceiptService.normalize(raw);
            if (!seen.add(serial)) {
                throw new InventoryException("DUPLICATE_SERIAL", "同一观察不能重复同一序列号");
            }
            String presence = localIds.contains(serial) ? PRESENT : FOUND;
            counts.insertObservationSerialIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, observationId,
                    serial, serial, presence, now);
        }
        int found = 0;
        int missing = 0;
        for (String serial : seen) {
            if (!localIds.contains(serial)) {
                found++;
            }
        }
        for (String serial : localIds) {
            if (!seen.contains(serial)) {
                missing++;
                counts.insertObservationSerialIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId,
                        observationId, serial, serial, MISSING, now);
            }
        }
        BigDecimal expected = decimal(line.get("snapshot_qty")).add(BigDecimal.valueOf(found - missing));
        if (counted.compareTo(expected) != 0) {
            throw new InventoryException("SERIAL_QTY_MISMATCH", "点数必须等于身份集合净变化");
        }
        observation.put("found", found);
        observation.put("missing", missing);
        return observation;
    }

    private Map<String, Object> view(String enterpriseId, String warehouseId, Map<String, Object> plan, CountMapper counts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", plan.get("id"));
        body.put("status", plan.get("status"));
        body.put("reasonCode", plan.get("reason_code"));
        body.put("approvalId", plan.get("approval_id"));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> line : counts.listLines(enterpriseId, warehouseId, String.valueOf(plan.get("id")))) {
            lines.add(lineView(line));
        }
        body.put("lines", lines);
        return body;
    }

    private static Map<String, Object> lineView(Map<String, Object> line) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", line.get("id"));
        body.put("balanceId", line.get("balance_id"));
        body.put("locationId", line.get("location_id"));
        body.put("snapshotQty", line.get("snapshot_qty"));
        body.put("reservedQty", line.get("reserved_qty"));
        body.put("countedQty", line.get("counted_qty"));
        body.put("status", line.get("status"));
        return body;
    }

    private Map<String, Object> persistObservation(String enterpriseId, String warehouseId, String planId, String lineId,
            String observationId, String qty, String actorId, int roundNo, boolean rejectLocalSerials) {
        requireId(observationId, "INVALID_OBSERVATION", "观察标识不能为空");
        requireId(actorId, "INVALID_ACTOR", "点数人不能为空");
        if (roundNo < 1) {
            throw new InventoryException("INVALID_ROUND", "点数轮次从1起");
        }
        BigDecimal counted = parseQty(qty);
        Timestamp now = Timestamp.from(clock.instant());
        CountMapper counts = session.getMapper(CountMapper.class);
        InventoryMapper inventory = session.getMapper(InventoryMapper.class);
        Map<String, Object> plan = requirePlan(counts, enterpriseId, warehouseId, planId);
        String planStatus = String.valueOf(plan.get("status"));
        if (!FROZEN.equals(planStatus) && !COUNTING.equals(planStatus)) {
            throw new InventoryException("COUNT_STATE_CONFLICT", "当前盘点状态不能点数");
        }
        Map<String, Object> existing = counts.lockObservation(enterpriseId, warehouseId, observationId);
        if (existing != null) {
            if (!lineId.equals(String.valueOf(existing.get("count_line_id")))
                    || counted.compareTo(decimal(existing.get("qty"))) != 0) {
                throw new InventoryException("OBSERVATION_CONFLICT", "观察身份已绑定其他点数");
            }
            return observationView(existing);
        }
        Map<String, Object> line = counts.lockLine(enterpriseId, warehouseId, planId, lineId);
        if (line == null) {
            throw new InventoryException("COUNT_LINE_NOT_FOUND", "没有该盘点快照行");
        }
        requireCountGate(inventory, counts, enterpriseId, warehouseId, planId, String.valueOf(line.get("location_id")),
                InventoryCodes.CMD_COUNT_OBSERVE);
        if (rejectLocalSerials && !session.getMapper(LocalSerialMapper.class)
                .lockActiveByBalance(enterpriseId, warehouseId, String.valueOf(line.get("balance_id"))).isEmpty()) {
            throw new InventoryException("SERIAL_SET_REQUIRED", "序列号盘点不能只录数量");
        }
        counts.insertObservationIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, planId, lineId,
                observationId, counted, actorId, roundNo, now);
        Map<String, Object> stored = counts.lockObservation(enterpriseId, warehouseId, observationId);
        if (!lineId.equals(String.valueOf(stored.get("count_line_id")))) {
            throw new InventoryException("OBSERVATION_CONFLICT", "观察身份已绑定其他点数");
        }
        counts.updateCounted(enterpriseId, warehouseId, lineId, counted, LINE_OBSERVED, now);
        if (FROZEN.equals(planStatus)) {
            counts.casPlanStatus(enterpriseId, warehouseId, planId, FROZEN, COUNTING, now);
        }
        return observationView(stored);
    }

    private static Map<String, Object> observationView(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("observationId", row.get("observation_id"));
        body.put("lineId", row.get("count_line_id"));
        body.put("qty", row.get("qty"));
        body.put("actorId", row.get("actor_id"));
        body.put("roundNo", row.get("round_no"));
        return body;
    }

    private static Map<String, Object> requirePlan(CountMapper counts, String enterpriseId, String warehouseId,
            String planId) {
        Map<String, Object> plan = counts.lockPlan(enterpriseId, warehouseId, planId);
        if (plan == null) {
            throw new InventoryException("COUNT_NOT_FOUND", "没有该盘点计划");
        }
        return plan;
    }

    private void applySerialIdentities(String enterpriseId, String warehouseId, String planId, Map<String, Object> line,
            String operationId, Timestamp now) {
        if (registry == null) {
            throw new InventoryException("REGISTRY_REQUIRED", "序列号盘点调整需要登记端口");
        }
        CountMapper counts = session.getMapper(CountMapper.class);
        LocalSerialMapper locals = session.getMapper(LocalSerialMapper.class);
        String lineId = String.valueOf(line.get("id"));
        String observationId = counts.latestObservationId(enterpriseId, warehouseId, lineId);
        String balanceId = String.valueOf(line.get("balance_id"));
        Map<String, Object> balance = session.getMapper(InventoryMapper.class).lockBalanceById(enterpriseId, warehouseId,
                balanceId);
        String skuId = String.valueOf(balance.get("sku_id"));
        String lotId = String.valueOf(balance.get("lot_id"));
        for (Map<String, Object> sight : counts.listObservationSerials(enterpriseId, warehouseId, observationId)) {
            String serial = String.valueOf(sight.get("normalized_serial"));
            String presence = String.valueOf(sight.get("presence_code"));
            if (MISSING.equals(presence)) {
                Map<String, Object> local = locals.lock(enterpriseId, warehouseId, serial);
                if (local == null) {
                    continue;
                }
                if (MISSING.equals(String.valueOf(local.get("state")))
                        || MISSING_PENDING.equals(String.valueOf(local.get("state")))) {
                    if (MISSING_PENDING.equals(String.valueOf(local.get("state")))) {
                        convergeMissing(enterpriseId, warehouseId, skuId, serial, operationId,
                                asLong(local.get("owner_epoch")), now, locals);
                    }
                    continue;
                }
                locals.updateState(enterpriseId, warehouseId, serial, balanceId, MISSING_PENDING, "MISSING", null,
                        asLong(local.get("owner_epoch")), now);
                convergeMissing(enterpriseId, warehouseId, skuId, serial, operationId, asLong(local.get("owner_epoch")),
                        now, locals);
            } else if (FOUND.equals(presence)) {
                Map<String, Object> local = locals.lock(enterpriseId, warehouseId, serial);
                if (local != null && SerialReceiptService.STATE_AUTHORIZED.equals(String.valueOf(local.get("state")))) {
                    continue;
                }
                try {
                    Map<String, Object> claimed = registry.claimFound(enterpriseId, skuId, serial, warehouseId,
                            operationId);
                    Map<String, Object> active = registry.activateFound(enterpriseId, skuId, serial, warehouseId,
                            operationId);
                    if (local == null) {
                        locals.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, serial, skuId, lotId,
                                balanceId, SerialReceiptService.STATE_AUTHORIZED, operationId,
                                String.valueOf(active.get("state")), null, now);
                    } else {
                        locals.updateState(enterpriseId, warehouseId, serial, balanceId,
                                SerialReceiptService.STATE_AUTHORIZED, String.valueOf(active.get("state")), null,
                                asLong(active.get("ownerEpoch"), claimed.get("ownerEpoch")), now);
                    }
                } catch (SerialRegistryUnavailableException error) {
                    throw new InventoryException("REGISTRY_UNAVAILABLE", "盘盈登记不可用，保持冻结");
                } catch (SerialRegistryConflictException error) {
                    throw new InventoryException(error.code(), error.getMessage());
                }
            }
        }
    }

    private void convergeMissing(String enterpriseId, String warehouseId, String skuId, String serial, String factRef,
            long epoch, Timestamp now, LocalSerialMapper locals) {
        try {
            registry.markMissing(enterpriseId, skuId, serial, warehouseId, factRef, epoch);
            Map<String, Object> local = locals.lock(enterpriseId, warehouseId, serial);
            locals.updateState(enterpriseId, warehouseId, serial,
                    local.get("balance_id") == null ? null : String.valueOf(local.get("balance_id")), MISSING, "MISSING",
                    null, epoch, now);
        } catch (SerialRegistryUnavailableException error) {
            throw new InventoryException("COUNT_REGISTRY_PENDING", "失踪事实尚未被登记确认");
        } catch (SerialRegistryConflictException error) {
            throw new InventoryException(error.code(), error.getMessage());
        }
    }

    private static void requireCountGate(InventoryMapper inventory, CountMapper counts, String enterpriseId,
            String warehouseId, String planId, String locationId, String command) {
        Map<String, Object> gate = inventory.lockGate(enterpriseId, warehouseId, locationId);
        if (gate == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库位门禁不存在");
        }
        if (!planId.equals(String.valueOf(gate.get("count_plan_id")))) {
            throw new InventoryException("GATE_CONFLICT", "门禁不属于该盘点计划");
        }
        Long expectedEpoch = counts.scopeEpoch(enterpriseId, warehouseId, planId, locationId);
        if (expectedEpoch != null && asLong(gate.get("fence_epoch")) != expectedEpoch) {
            throw new InventoryException("GATE_EPOCH_MISMATCH", "门禁代际与盘点范围不一致");
        }
        String decision = InventoryPolicy.decideGate(String.valueOf(gate.get("state")), command);
        if (!InventoryCodes.DECISION_ALLOW.equals(decision)) {
            throw new InventoryException("STOCK_FROZEN", "门禁拒绝盘点命令：" + decision);
        }
    }

    private static boolean frozenOrLater(String status) {
        return FROZEN.equals(status) || COUNTING.equals(status) || REVIEWING.equals(status) || APPROVED.equals(status)
                || APPLYING.equals(status) || COMPLETED.equals(status);
    }

    private static void requireId(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new InventoryException(code, message);
        }
    }

    private static BigDecimal parseQty(String qty) {
        if (qty == null || qty.isBlank()) {
            throw new InventoryException("INVALID_QTY", "点数不能为空");
        }
        BigDecimal value = new BigDecimal(qty);
        if (value.signum() < 0) {
            throw new InventoryException("INVALID_QTY", "点数不能为负");
        }
        return value;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static long asLong(Object primary, Object fallback) {
        if (primary instanceof Number number) {
            return number.longValue();
        }
        return asLong(fallback);
    }
}
