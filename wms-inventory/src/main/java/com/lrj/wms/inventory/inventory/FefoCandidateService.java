package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.InventoryPolicy;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.infrastructure.FefoCandidateMapper;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 按 FEFO 列出当前可分配候选。过期、非存储位、冻结门禁不进入列表。 */
public final class FefoCandidateService {
    public static final String LOCATION_STORAGE = "STORAGE";
    public static final int DEFAULT_LIMIT = 16;

    private final SqlSession session;
    private final Clock clock;

    public FefoCandidateService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public List<Map<String, Object>> list(String enterpriseId, String warehouseId, String ownerId, String skuId,
            String allocationPolicy, int limit) {
        if (!InventoryCodes.ALLOC_FEFO.equals(InventoryCodes.requireAllocationPolicy(allocationPolicy))) {
            throw new InventoryException("INVALID_ALLOCATION_POLICY", "本查询只接受 FEFO");
        }
        if (limit < 1 || limit > 64) {
            throw new InventoryException("INVALID_LIMIT", "FEFO 候选页大小必须在 1 到 64");
        }
        Instant now = clock.instant();
        List<Map<String, Object>> rows = session.getMapper(FefoCandidateMapper.class)
                .listGoodLots(enterpriseId, warehouseId, ownerId, skuId, limit);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!LOCATION_STORAGE.equals(String.valueOf(row.get("location_type")))
                    || !MasterdataCodes.STATE_ACTIVE.equals(String.valueOf(row.get("location_state")))
                    || !MasterdataCodes.GATE_OPEN.equals(String.valueOf(row.get("gate_state")))) {
                continue;
            }
            Instant expiresAt = ExpiryPolicy.instantOf(row.get("expires_at"));
            if (!ExpiryPolicy.satisfied(expiresAt, now)) {
                continue;
            }
            Quantity onHand = quantity(row.get("on_hand_qty"));
            Quantity reserved = quantity(row.get("reserved_qty"));
            Quantity claim = quantity(row.get("free_execution_claim_qty"));
            Quantity available = InventoryPolicy.nonSerialAvailable(onHand, reserved, claim, true,
                    InventoryCodes.QUALITY_GOOD, true, false);
            if (!available.isPositive()) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("balanceId", row.get("balance_id"));
            body.put("lotId", row.get("lot_id"));
            body.put("locationId", row.get("location_id"));
            body.put("expiresAt", expiresAt);
            body.put("availableQty", available.toPlainString());
            candidates.add(body);
        }
        return candidates;
    }

    private static Quantity quantity(Object value) {
        BigDecimal stripped = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        return Quantity.of(stripped, scale);
    }
}
