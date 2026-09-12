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
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 库存限制。占用 reserved，不复用盘点 location_gate。 */
public final class StockHoldService {
    public static final String OPEN = "OPEN";
    public static final String RELEASED = "RELEASED";

    private final SqlSession session;
    private final Clock clock;

    public StockHoldService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> create(String enterpriseId, String warehouseId, String clientOperationId, String actorId,
            String balanceId, BigDecimal qty, String reason, String evidenceRefs) {
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        require(balanceId, "INVALID_ARGUMENT", "限制必须指定库存桶");
        require(reason, "INVALID_ARGUMENT", "原因不能为空");
        if (qty == null || qty.signum() <= 0) {
            throw new InventoryException("INVALID_QUANTITY", "限制数量必须为正");
        }
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> existing = docs.getHoldByKey(enterpriseId, warehouseId, clientOperationId);
        if (existing != null) {
            if (!balanceId.equals(String.valueOf(existing.get("balance_id")))
                    || qty.compareTo(decimal(existing.get("qty"))) != 0) {
                throw new InventoryException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键限制内容不一致");
            }
            return view(existing);
        }
        Map<String, Object> balance = session.getMapper(InventoryHttpQueryMapper.class)
                .getBalance(enterpriseId, warehouseId, balanceId);
        if (balance == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库存桶不存在");
        }
        Timestamp now = Timestamp.from(clock.instant());
        new InventoryApplicationService(session, clock).hold(enterpriseId, warehouseId, clientOperationId,
                clientOperationId, actorId, balanceId, quantity(qty));
        docs.insertHoldIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, clientOperationId, balanceId,
                String.valueOf(balance.get("location_id")), String.valueOf(balance.get("sku_id")),
                String.valueOf(balance.get("lot_id")), qty, reason, evidenceRefs, actorId, clientOperationId, OPEN, now);
        return view(docs.getHoldByKey(enterpriseId, warehouseId, clientOperationId));
    }

    public Map<String, Object> release(String enterpriseId, String warehouseId, String holdId, String clientOperationId,
            String actorId, String reason, long expectedVersion) {
        require(holdId, "INVALID_ARGUMENT", "限制标识不能为空");
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> hold = docs.lockHold(enterpriseId, warehouseId, holdId);
        if (hold == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "库存限制不存在");
        }
        if (RELEASED.equals(String.valueOf(hold.get("state")))) {
            return view(hold);
        }
        if (asLong(hold.get("version")) != expectedVersion) {
            throw new InventoryException("VERSION_CONFLICT", "限制版本冲突");
        }
        Timestamp now = Timestamp.from(clock.instant());
        String releaseOp = clientOperationId;
        new InventoryApplicationService(session, clock).releaseHold(enterpriseId, warehouseId, releaseOp, holdId, actorId,
                String.valueOf(hold.get("balance_id")), quantity(decimal(hold.get("qty"))));
        if (docs.casReleaseHold(enterpriseId, warehouseId, holdId, expectedVersion, actorId, reason, releaseOp, now) != 1) {
            throw new InventoryException("HOLD_STATE_CONFLICT", "限制已变化，不能释放");
        }
        return view(docs.lockHold(enterpriseId, warehouseId, holdId));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("holdId", row.get("id"));
        body.put("balanceId", row.get("balance_id"));
        body.put("qty", row.get("qty"));
        body.put("reason", row.get("reason"));
        body.put("operationId", row.get("operation_id"));
        body.put("state", row.get("state"));
        body.put("version", row.get("version"));
        return body;
    }

    private static Quantity quantity(BigDecimal qty) {
        return Quantity.of(qty, Math.max(qty.scale(), 0));
    }

    private static void require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new InventoryException(code, message);
        }
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal qty ? qty : new BigDecimal(String.valueOf(value));
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
