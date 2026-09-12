package com.lrj.wms.inventory.domain;

import com.lrj.wms.inventory.domain.infrastructure.DomainCommandMapper;
import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper;
import com.lrj.wms.inventory.query.InventoryHttpQueryMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 同仓移库单据。跨仓调拨不是本用例。 */
public final class WarehouseMoveService {
    public static final String ACCEPTED = "ACCEPTED";

    private final SqlSession session;
    private final Clock clock;

    public WarehouseMoveService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> move(String enterpriseId, String warehouseId, String clientOperationId, String actorId,
            String sourceBalanceId, String targetLocationId, BigDecimal qty, String unit, String reason) {
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        require(sourceBalanceId, "INVALID_ARGUMENT", "源库存桶不能为空");
        require(targetLocationId, "INVALID_ARGUMENT", "目标库位不能为空");
        require(reason, "INVALID_ARGUMENT", "原因不能为空");
        if (qty == null || qty.signum() <= 0) {
            throw new InventoryException("INVALID_QUANTITY", "移库数量必须为正");
        }
        DomainCommandMapper docs = session.getMapper(DomainCommandMapper.class);
        Map<String, Object> existing = docs.getMoveByKey(enterpriseId, warehouseId, clientOperationId);
        if (existing != null) {
            if (!sourceBalanceId.equals(String.valueOf(existing.get("source_balance_id")))
                    || !targetLocationId.equals(String.valueOf(existing.get("target_location_id")))
                    || qty.compareTo(decimal(existing.get("qty"))) != 0) {
                throw new InventoryException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键移库内容不一致");
            }
            return view(existing);
        }
        Map<String, Object> source = session.getMapper(InventoryHttpQueryMapper.class)
                .getBalance(enterpriseId, warehouseId, sourceBalanceId);
        if (source == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "源库存桶不存在");
        }
        if (session.getMapper(MasterdataHttpMapper.class).getLocation(enterpriseId, warehouseId, targetLocationId) == null) {
            throw new InventoryException("LOCATION_NOT_FOUND", "目标库位不存在");
        }
        if (targetLocationId.equals(String.valueOf(source.get("location_id")))) {
            throw new InventoryException("INVALID_QUANTITY", "移库源与目标不能相同");
        }
        Timestamp now = Timestamp.from(clock.instant());
        StockBucketKey from = StockBucketKey.of(enterpriseId, warehouseId, String.valueOf(source.get("owner_id")),
                String.valueOf(source.get("location_id")), String.valueOf(source.get("sku_id")),
                String.valueOf(source.get("lot_id")), String.valueOf(source.get("quality_code")));
        StockBucketKey to = StockBucketKey.of(enterpriseId, warehouseId, from.ownerId(), targetLocationId, from.skuId(),
                from.lotId(), from.qualityCode());
        new InventoryApplicationService(session, clock).move(enterpriseId, warehouseId, clientOperationId,
                clientOperationId, actorId, from, to, Quantity.of(qty, qty.scale() < 0 ? 0 : qty.scale()), false);
        Map<String, Object> target = session.getMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class)
                .lockBalanceByDimension(enterpriseId, warehouseId, to.ownerId(), to.locationId(), to.skuId(), to.lotId(),
                        to.qualityCode());
        String moveId = UUID.randomUUID().toString();
        docs.insertMoveIgnore(moveId, enterpriseId, warehouseId, clientOperationId, sourceBalanceId, targetLocationId,
                target == null ? null : String.valueOf(target.get("id")), qty, unit == null ? "EA" : unit, reason,
                actorId, clientOperationId, ACCEPTED, now);
        return view(docs.getMoveByKey(enterpriseId, warehouseId, clientOperationId));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("sourceBalanceId", row.get("source_balance_id"));
        body.put("targetLocationId", row.get("target_location_id"));
        body.put("targetBalanceId", row.get("target_balance_id"));
        body.put("qty", row.get("qty"));
        body.put("operationId", row.get("operation_id"));
        body.put("state", row.get("state"));
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
