package com.lrj.wms.inventory.domain;

import com.lrj.wms.inventory.domain.infrastructure.DomainCommandMapper;
import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.query.InventoryHttpQueryMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 独立调整单。审批后才能过账，不复用 count_plan 应用行。 */
public final class WarehouseAdjustmentService {
    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String APPLIED = "APPLIED";

    private final SqlSession session;
    private final Clock clock;

    public WarehouseAdjustmentService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> create(String enterpriseId, String warehouseId, String clientOperationId, String actorId,
            String balanceId, BigDecimal deltaQty, String reason, String countLineId, String evidenceRefs) {
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        require(balanceId, "INVALID_ARGUMENT", "调整必须指定库存桶");
        require(reason, "INVALID_ARGUMENT", "原因不能为空");
        if (deltaQty == null || deltaQty.signum() == 0) {
            throw new InventoryException("INVALID_QUANTITY", "调整增量不能为0");
        }
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> existing = docs.getAdjustmentByKey(enterpriseId, warehouseId, clientOperationId);
        if (existing != null) {
            if (!balanceId.equals(String.valueOf(existing.get("balance_id")))
                    || deltaQty.compareTo(decimal(existing.get("delta_qty"))) != 0) {
                throw new InventoryException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键调整内容不一致");
            }
            return view(existing);
        }
        if (session.getMapper(InventoryHttpQueryMapper.class).getBalance(enterpriseId, warehouseId, balanceId) == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库存桶不存在");
        }
        Timestamp now = Timestamp.from(clock.instant());
        docs.insertAdjustmentIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, clientOperationId,
                countLineId, balanceId, deltaQty, reason, evidenceRefs, actorId, DRAFT, now);
        return view(docs.getAdjustmentByKey(enterpriseId, warehouseId, clientOperationId));
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String adjustmentId) {
        Map<String, Object> row = session.getMapper(DomainCommandMapper.class).getAdjustment(enterpriseId, warehouseId,
                adjustmentId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "调整单不存在");
        }
        return view(row);
    }

    public List<Map<String, Object>> list(String enterpriseId, String warehouseId, String cursor, int limit) {
        int page = Math.min(Math.max(limit, 1), 50);
        return session.getMapper(DomainCommandMapper.class).listAdjustments(enterpriseId, warehouseId, cursor, page);
    }

    public Map<String, Object> decide(String enterpriseId, String warehouseId, String adjustmentId, String actorId,
            String decision, String reason, long expectedVersion) {
        if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
            throw new InventoryException("INVALID_ARGUMENT", "审批结论只能是APPROVED或REJECTED");
        }
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> row = docs.lockAdjustment(enterpriseId, warehouseId, adjustmentId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "调整单不存在");
        }
        if (decision.equals(String.valueOf(row.get("decision"))) && !DRAFT.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (docs.casAdjustmentDecision(enterpriseId, warehouseId, adjustmentId, expectedVersion, decision, actorId,
                reason, decision, Timestamp.from(clock.instant())) != 1) {
            throw new InventoryException("ADJUSTMENT_STATE_CONFLICT", "调整单不是待审或版本冲突");
        }
        return view(docs.lockAdjustment(enterpriseId, warehouseId, adjustmentId));
    }

    public Map<String, Object> apply(String enterpriseId, String warehouseId, String adjustmentId, String clientOperationId,
            String actorId, long expectedVersion) {
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> row = docs.lockAdjustment(enterpriseId, warehouseId, adjustmentId);
        if (row == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "调整单不存在");
        }
        if (APPLIED.equals(String.valueOf(row.get("state")))) {
            return view(row);
        }
        if (!APPROVED.equals(String.valueOf(row.get("state")))) {
            throw new InventoryException("ADJUSTMENT_STATE_CONFLICT", "未审批不能应用调整");
        }
        BigDecimal delta = decimal(row.get("delta_qty"));
        new InventoryApplicationService(session, clock).adjust(enterpriseId, warehouseId, clientOperationId,
                adjustmentId, actorId, String.valueOf(row.get("balance_id")),
                Quantity.of(delta, Math.max(delta.scale(), 0)));
        if (docs.casAdjustmentApplied(enterpriseId, warehouseId, adjustmentId, expectedVersion, clientOperationId,
                Timestamp.from(clock.instant())) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "调整过账版本冲突");
        }
        return view(docs.lockAdjustment(enterpriseId, warehouseId, adjustmentId));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("countLineId", row.get("count_line_id"));
        body.put("balanceId", row.get("balance_id"));
        body.put("deltaQty", row.get("delta_qty"));
        body.put("reason", row.get("reason"));
        body.put("decision", row.get("decision"));
        body.put("applyOperationId", row.get("apply_operation_id"));
        body.put("state", row.get("state"));
        body.put("status", row.get("state"));
        body.put("version", row.get("version"));
        return body;
    }

    private static void require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new InventoryException(code, message);
        }
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal qty ? qty : new BigDecimal(String.valueOf(value));
    }
}
