package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.compat.CompatibilityGate;
import com.lrj.wms.inventory.jobs.JobRunException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 导出数量事实快照。水位不齐不能完成；完成后重拉同一 snapshotId 内容不变。
 */
public final class SnapshotExportService {
    public static final int PAGE_LIMIT = 100;
    public static final String SCENARIO = "WMS_ONHAND_QTY";

    private final SqlSession session;
    private final Clock clock;

    public SnapshotExportService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> export(String enterpriseId, String warehouseId, String cutoffId, Timestamp closedAt,
            String sourceWatermark, String postingWatermark, String receiptWatermark) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, cutoffId);
        if (closedAt == null) {
            throw new JobRunException("INVALID_CUTOFF", "快照必须带稳定关闭时刻");
        }
        if (blank(sourceWatermark) || blank(postingWatermark) || blank(receiptWatermark)) {
            throw new JobRunException("SOURCE_INCOMPLETE", "三方水位不齐不能发布快照");
        }
        Timestamp now = Timestamp.from(clock.instant());
        String scopeJson = "{\"warehouseIds\":[\"" + warehouseId + "\"]}";
        String digest = sha256(enterpriseId + "/" + warehouseId + "/" + SCENARIO + "/" + cutoffId);
        String watermarks = "{\"source\":\"" + sourceWatermark + "\",\"posting\":\"" + postingWatermark
                + "\",\"receipt\":\"" + receiptWatermark + "\"}";
        SnapshotMapper mapper = session.getMapper(SnapshotMapper.class);
        mapper.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, SCENARIO, cutoffId, closedAt,
                digest, scopeJson, watermarks, WarehouseQuantityFact.SCHEMA_VERSION, now);
        Map<String, Object> existing = mapper.lockByKey(enterpriseId, warehouseId, SCENARIO, cutoffId, digest);
        if (existing == null) {
            throw new JobRunException("SNAPSHOT_MISSING", "快照未创建");
        }
        String snapshotId = String.valueOf(existing.get("id"));
        if ("COMPLETE".equals(String.valueOf(existing.get("state")))) {
            return get(enterpriseId, warehouseId, snapshotId);
        }
        StringJoiner lines = new StringJoiner("\n");
        int rows = 0;
        for (Map<String, Object> balance : mapper.listBalances(enterpriseId, warehouseId, closedAt, PAGE_LIMIT)) {
            Map<String, Object> fact = WarehouseQuantityFact.onHand(String.valueOf(balance.get("id")) + "/" + cutoffId,
                    enterpriseId, warehouseId, String.valueOf(balance.get("owner_id")),
                    String.valueOf(balance.get("sku_id")), String.valueOf(balance.get("lot_id")), null,
                    decimal(balance.get("on_hand_qty")), String.valueOf(balance.get("base_unit")), cutoffId,
                    postingWatermark);
            CompatibilityGate.requireQuantityFact(fact);
            lines.add(toJson(fact));
            rows++;
        }
        String payload = lines.toString();
        String partHash = sha256(payload);
        mapper.insertPartIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, snapshotId, 1, payload, rows,
                partHash, now);
        String manifest = "{\"schemaVersion\":" + WarehouseQuantityFact.SCHEMA_VERSION + ",\"snapshotId\":\""
                + snapshotId + "\",\"scenarioCode\":\"" + SCENARIO + "\",\"cutoffId\":\"" + cutoffId
                + "\",\"complete\":true,\"units\":[\"EA\"],\"parts\":[{\"partNo\":1,\"rowCount\":" + rows
                + ",\"sha256\":\"" + partHash + "\"}]}";
        if (mapper.casComplete(enterpriseId, warehouseId, snapshotId, "COMPLETE", manifest, now, now) != 1
                && mapper.get(enterpriseId, warehouseId, snapshotId) == null) {
            throw new JobRunException("CONFLICT", "快照完成冲突");
        }
        return get(enterpriseId, warehouseId, snapshotId);
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String snapshotId) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, snapshotId);
        SnapshotMapper mapper = session.getMapper(SnapshotMapper.class);
        Map<String, Object> snapshot = mapper.get(enterpriseId, warehouseId, snapshotId);
        if (snapshot == null) {
            throw new JobRunException("SNAPSHOT_MISSING", "快照不存在");
        }
        List<Map<String, Object>> parts = mapper.listParts(enterpriseId, warehouseId, snapshotId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshot.get("id"));
        body.put("state", snapshot.get("state"));
        body.put("manifest", snapshot.get("manifest_json"));
        body.put("schemaVersion", snapshot.get("schema_version"));
        body.put("parts", parts.stream().map(part -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partNo", part.get("part_no"));
            item.put("rowCount", part.get("row_count"));
            item.put("sha256", part.get("sha256"));
            item.put("payload", part.get("payload"));
            return item;
        }).toList());
        return body;
    }

    private static void require(String enterpriseId, String warehouseId, String id) {
        if (blank(enterpriseId) || blank(warehouseId) || blank(id)) {
            throw new JobRunException("INVALID_SCOPE", "快照必须带企业/仓");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String toJson(Map<String, Object> fact) {
        StringJoiner json = new StringJoiner(",", "{", "}");
        fact.forEach((key, value) -> {
            if (value == null) {
                json.add("\"" + key + "\":null");
            } else if (value instanceof Number) {
                json.add("\"" + key + "\":" + value);
            } else {
                json.add("\"" + key + "\":\"" + value + "\"");
            }
        });
        return json.toString();
    }
}
