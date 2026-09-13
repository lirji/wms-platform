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
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;
import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 导出数量事实快照。水位不齐不能完成；完成后重拉同一 snapshotId 内容不变。
 */
public final class SnapshotExportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
        if (closedAt == null || closedAt.toInstant().isAfter(clock.instant())) {
            throw new JobRunException("INVALID_CUTOFF", "快照必须带稳定关闭时刻");
        }
        if (blank(sourceWatermark) || blank(postingWatermark) || blank(receiptWatermark)) {
            throw new JobRunException("SOURCE_INCOMPLETE", "三方水位不齐不能发布快照");
        }
        requireEvidence(enterpriseId,warehouseId,cutoffId,closedAt,sourceWatermark,postingWatermark,receiptWatermark);
        Timestamp now = Timestamp.from(clock.instant());
        String scopeJson = JSON.writeValueAsString(Map.of("warehouseIds", List.of(warehouseId)));
        String digest = sha256(JSON.writeValueAsString(List.of(enterpriseId, warehouseId, SCENARIO, cutoffId)));
        String watermarks = JSON.writeValueAsString(Map.of("source", sourceWatermark, "posting", postingWatermark,
                "receipt", receiptWatermark));
        SnapshotMapper mapper = session.getMapper(SnapshotMapper.class);
        mapper.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, SCENARIO, cutoffId, closedAt,
                digest, scopeJson, watermarks, WarehouseQuantityFact.SCHEMA_VERSION, now);
        Map<String, Object> existing = mapper.lockByKey(enterpriseId, warehouseId, SCENARIO, cutoffId, digest);
        if (existing == null) {
            throw new JobRunException("SNAPSHOT_MISSING", "快照未创建");
        }
        if (!ExpiryPolicy.instantOf(existing.get("closed_at")).equals(closedAt.toInstant())
                || !JSON.readTree(String.valueOf(existing.get("source_watermarks"))).equals(JSON.readTree(watermarks))) {
            throw new JobRunException("CONFLICT", "同一截止身份的关闭时刻和水位不能修改");
        }
        String snapshotId = String.valueOf(existing.get("id"));
        if ("COMPLETE".equals(String.valueOf(existing.get("state")))) {
            return get(enterpriseId, warehouseId, snapshotId);
        }
        // 每次最多生成一段，调用方提交后以同一截止身份续跑；不会持有全量导出的长事务。
        String afterId = (String) existing.get("last_balance_id");
        List<Map<String, Object>> balances = mapper.listBalances(enterpriseId, warehouseId, closedAt, afterId, PAGE_LIMIT + 1);
        boolean complete = balances.size() <= PAGE_LIMIT;
        List<Map<String, Object>> page = balances.stream().limit(PAGE_LIMIT).toList();
        TreeSet<String> units = new TreeSet<>();
        if (existing.get("units_json") != null) {
            JSON.readTree(String.valueOf(existing.get("units_json"))).forEach(unit -> units.add(unit.asString()));
        }
        StringJoiner lines = new StringJoiner("\n");
        for (Map<String, Object> balance : page) {
            if (balance.get("on_hand_qty") == null || balance.get("base_unit") == null) {
                throw new JobRunException("SOURCE_INCOMPLETE", "库存缺少截止前流水或权威单位，不能发布完整快照");
            }
            String unit = String.valueOf(balance.get("base_unit"));
            units.add(unit);
            if (units.size() > 200) throw new JobRunException("EXPORT_LIMIT", "单位集合超过单快照预算，请缩小范围");
            Map<String, Object> fact = WarehouseQuantityFact.onHand(String.valueOf(balance.get("id")) + "/" + cutoffId,
                    enterpriseId, warehouseId, String.valueOf(balance.get("owner_id")),
                    String.valueOf(balance.get("sku_id")), String.valueOf(balance.get("lot_id")), null,
                    decimal(balance.get("on_hand_qty")), unit, cutoffId, postingWatermark);
            CompatibilityGate.requireQuantityFact(fact);
            lines.add(JSON.writeValueAsString(fact));
        }
        int parts = ((Number) existing.get("part_count")).intValue();
        long rows = ((Number) existing.get("total_rows")).longValue() + page.size();
        if (!page.isEmpty() || parts == 0) {
            String payload = lines.toString();
            if (mapper.insertPartIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, snapshotId, ++parts,
                    payload, page.size(), sha256(payload), now) != 1) {
                throw new JobRunException("CONFLICT", "快照分段冲突");
            }
        }
        if (!page.isEmpty()) afterId = String.valueOf(page.getLast().get("id"));
        if (mapper.checkpoint(enterpriseId, warehouseId, snapshotId, afterId, parts, rows,
                JSON.writeValueAsString(units), ((Number) existing.get("version")).longValue(), now) != 1) {
            throw new JobRunException("CONFLICT", "快照检查点冲突");
        }
        String manifest = JSON.writeValueAsString(Map.of("schemaVersion", WarehouseQuantityFact.SCHEMA_VERSION,
                "snapshotId", snapshotId, "scenarioCode", SCENARIO, "cutoffId", cutoffId, "complete", true,
                "units", units, "partCount", parts, "rowCount", rows));
        if (complete && mapper.casComplete(enterpriseId, warehouseId, snapshotId, "COMPLETE", manifest, now, now) != 1) {
            throw new JobRunException("CONFLICT", "快照完成冲突");
        }
        return get(enterpriseId, warehouseId, snapshotId);
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String snapshotId) {
        return get(enterpriseId, warehouseId, snapshotId, 0);
    }

    /** 每次读取一个已提交分段，nextPartNo 用于续取，避免把全部内容加载到内存。 */
    public Map<String, Object> get(String enterpriseId, String warehouseId, String snapshotId, int afterPart) {
        if (afterPart < 0) throw new IllegalArgumentException("afterPart 必须非负");
        RootContext.unbind();
        require(enterpriseId, warehouseId, snapshotId);
        SnapshotMapper mapper = session.getMapper(SnapshotMapper.class);
        Map<String, Object> snapshot = mapper.get(enterpriseId, warehouseId, snapshotId);
        if (snapshot == null) {
            throw new JobRunException("SNAPSHOT_MISSING", "快照不存在");
        }
        // 已生成的历史分段也不能绕过证明校验，不能只限制新建入口。
        var watermarks=JSON.readTree(String.valueOf(snapshot.get("source_watermarks")));
        requireEvidence(enterpriseId,warehouseId,String.valueOf(snapshot.get("cutoff_id")),
                Timestamp.from(com.lrj.wms.runtime.db.DatabaseInstants.require(snapshot.get("closed_at"))),
                watermarks.path("source").asString(),watermarks.path("posting").asString(),watermarks.path("receipt").asString());
        List<Map<String, Object>> parts = mapper.listParts(enterpriseId, warehouseId, snapshotId, afterPart);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshot.get("id"));
        body.put("state", snapshot.get("state"));
        body.put("manifest", snapshot.get("manifest_json"));
        body.put("schemaVersion", snapshot.get("schema_version"));
        body.put("partCount", snapshot.get("part_count"));
        body.put("rowCount", snapshot.get("total_rows"));
        body.put("nextPartNo", parts.size() > 1 ? parts.getFirst().get("part_no") : null);
        body.put("parts", parts.stream().limit(1).map(part -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partNo", part.get("part_no"));
            item.put("rowCount", part.get("row_count"));
            item.put("sha256", part.get("sha256"));
            item.put("payload", part.get("payload"));
            return item;
        }).toList());
        return body;
    }

    private void requireEvidence(String e,String w,String cutoffId,Timestamp closedAt,String source,String posting,String receipt) {
        var proof=session.getMapper(SnapshotMapper.class).verifiedWindow(e,w,cutoffId);
        if(proof==null || !closedAt.toInstant().equals(com.lrj.wms.runtime.db.DatabaseInstants.require(proof.get("closed_at")))
                || !java.util.Objects.equals(source,proof.get("source_watermark"))
                || !java.util.Objects.equals(posting,proof.get("posting_watermark"))
                || !java.util.Objects.equals(receipt,proof.get("receipt_watermark")))
            throw new JobRunException("SOURCE_INCOMPLETE","来源、库存、回执与历史冻结尚未取得匹配证明");
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

}
