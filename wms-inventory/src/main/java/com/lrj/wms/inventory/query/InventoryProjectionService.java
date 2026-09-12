package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.jobs.JobRunException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 按 eventId 去重、按 aggregateVersion 顺序更新投影。乱序先入 inbox，缺口不覆盖。
 * 重建从权威余额拷贝，追平后才切世代。查询带 asOf/lag，不替代写校验。
 */
public final class InventoryProjectionService {
    public static final String CONSUMER = "inventory-view";
    public static final String NAME = "inventory_view";

    private final SqlSession session;
    private final Clock clock;

    public InventoryProjectionService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> apply(String enterpriseId, String warehouseId, String eventId, String aggregateId,
            long aggregateVersion, String eventType, String payload, Timestamp occurredAt, String ownerId,
            String locationId, String skuId, String lotId, String qualityCode) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, eventId, aggregateId);
        ProjectionMapper views = session.getMapper(ProjectionMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        ensureCheckpoint(views, enterpriseId, warehouseId, now);
        int existed = views.countInbox(enterpriseId, warehouseId, CONSUMER, eventId);
        views.insertInboxIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, CONSUMER, eventId, aggregateId,
                aggregateVersion, eventType, payload == null ? "{}" : payload, occurredAt == null ? now : occurredAt, now);
        if (existed > 0) {
            return Map.of("replayed", true, "applied", false);
        }
        long generation = liveGeneration(views, enterpriseId, warehouseId);
        boolean applied = drain(views, enterpriseId, warehouseId, generation, aggregateId, ownerId, locationId, skuId,
                lotId, qualityCode, now);
        return Map.of("replayed", false, "applied", applied, "generation", generation);
    }

    /** 从权威余额重建影子世代，高水位不低于当前才切换。 */
    public Map<String, Object> rebuild(String enterpriseId, String warehouseId) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, "x", "x");
        ProjectionMapper views = session.getMapper(ProjectionMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        ensureCheckpoint(views, enterpriseId, warehouseId, now);
        Map<String, Object> checkpoint = views.lockCheckpoint(enterpriseId, warehouseId, NAME);
        long live = asLong(checkpoint.get("live_generation"));
        long next = live + 1;
        views.casCheckpoint(enterpriseId, warehouseId, NAME, live, next, string(checkpoint.get("last_event_id")),
                timestampOf(checkpoint.get("last_event_time")), asLong(checkpoint.get("version")), now);
        views.copyBalances(enterpriseId, warehouseId, next, now);
        Long rebuildWater = views.highWater(enterpriseId, warehouseId, next);
        Long liveWater = views.highWater(enterpriseId, warehouseId, live);
        if (liveWater != null && (rebuildWater == null || rebuildWater < liveWater)) {
            throw new JobRunException("REBUILD_LAG", "重建未追平当前投影，拒绝切换");
        }
        Map<String, Object> after = views.lockCheckpoint(enterpriseId, warehouseId, NAME);
        if (views.casCheckpoint(enterpriseId, warehouseId, NAME, next, null, string(after.get("last_event_id")),
                timestampOf(after.get("last_event_time")), asLong(after.get("version")), now) != 1) {
            throw new JobRunException("CONFLICT", "投影切换冲突");
        }
        return Map.of("liveGeneration", next, "switched", true);
    }

    public Map<String, Object> query(String enterpriseId, String warehouseId, String skuId, int limit) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, "x", "x");
        ProjectionMapper views = session.getMapper(ProjectionMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        ensureCheckpoint(views, enterpriseId, warehouseId, now);
        long generation = liveGeneration(views, enterpriseId, warehouseId);
        List<Map<String, Object>> items = views.listView(enterpriseId, warehouseId, generation, blankToNull(skuId),
                limit <= 0 ? 50 : Math.min(limit, 200));
        Timestamp asOf = timestampOf(views.maxAsOf(enterpriseId, warehouseId, generation));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("limit", items.size());
        body.put("asOf", asOf == null ? null : asOf.toInstant().toString());
        body.put("lagSeconds", asOf == null ? 0 : Math.max(0, Duration.between(asOf.toInstant(), clock.instant()).toSeconds()));
        body.put("generation", generation);
        return body;
    }

    private boolean drain(ProjectionMapper views, String enterpriseId, String warehouseId, long generation,
            String aggregateId, String ownerId, String locationId, String skuId, String lotId, String qualityCode,
            Timestamp now) {
        boolean applied = false;
        while (true) {
            Map<String, Object> current = views.lockView(enterpriseId, warehouseId, generation, aggregateId);
            long expected = current == null ? 0L : asLong(current.get("source_version"));
            Map<String, Object> next = views.findInboxVersion(enterpriseId, warehouseId, CONSUMER, aggregateId,
                    expected + 1);
            if (next == null) {
                return applied;
            }
            if (!InventoryCodes.EVENT_BALANCE_CHANGED.equals(String.valueOf(next.get("event_type")))) {
                return applied;
            }
            String payload = String.valueOf(next.get("payload"));
            BigDecimal onHand = decimalField(payload, "onHandAfter");
            BigDecimal reserved = decimalField(payload, "reservedAfter");
            Timestamp asOf = timestampOf(next.get("occurred_at"));
            long version = asLong(next.get("aggregate_version"));
            if (current == null) {
                views.insertView(aggregateId, enterpriseId, warehouseId, generation, ownerId, locationId, skuId, lotId,
                        qualityCode, onHand, reserved, BigDecimal.ZERO, version, asOf, now);
            } else if (views.casView(enterpriseId, warehouseId, generation, aggregateId, onHand, reserved, version,
                    expected, asOf, now) != 1) {
                return applied;
            }
            applied = true;
        }
    }

    private void ensureCheckpoint(ProjectionMapper views, String enterpriseId, String warehouseId, Timestamp now) {
        views.insertCheckpointIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, NAME, now);
    }

    private long liveGeneration(ProjectionMapper views, String enterpriseId, String warehouseId) {
        Map<String, Object> checkpoint = views.lockCheckpoint(enterpriseId, warehouseId, NAME);
        return checkpoint == null ? 0L : asLong(checkpoint.get("live_generation"));
    }

    private static void require(String enterpriseId, String warehouseId, String eventId, String aggregateId) {
        if (enterpriseId == null || enterpriseId.isBlank() || warehouseId == null || warehouseId.isBlank()
                || eventId == null || eventId.isBlank() || aggregateId == null || aggregateId.isBlank()) {
            throw new JobRunException("INVALID_SCOPE", "投影必须带企业/仓/事件");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Timestamp timestampOf(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value == null) {
            return null;
        }
        return Timestamp.from(ExpiryPolicy.instantOf(value));
    }

    private static BigDecimal decimalField(String json, String name) {
        String key = "\"" + name + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return BigDecimal.ZERO;
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(json.substring(start, end));
    }
}
