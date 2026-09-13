package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 来源事实按稳定 cutoff 对账；本地不变量按单页一致性快照复核。缺三方水位不得判丢失。
 * 前台列表不跑巡检，巡检有行数预算。
 */
public final class StockInternalReconcile {
    public static final int PAGE_LIMIT = 100;
    public static final Duration GRACE = Duration.ofMinutes(15);
    public static final String BALANCE_LEDGER = "BALANCE_LEDGER";
    public static final String RESERVED_MISMATCH = "RESERVED_MISMATCH";
    public static final String SERIAL_QTY = "SERIAL_QTY";
    public static final String SOURCE_POSTING = "SOURCE_POSTING";
    public static final String WATERMARK = "WATERMARK";
    public static final String QTY_MISMATCH = "QTY_MISMATCH";
    public static final String SERIAL_CONFLICT = "SERIAL_CONFLICT";
    public static final String SOURCE_INCOMPLETE = "SOURCE_INCOMPLETE";
    public static final String LATE_ARRIVAL = "LATE_ARRIVAL";
    public static final String MISSING_LEFT = "MISSING_LEFT";
    public static final String MISSING_RIGHT = "MISSING_RIGHT";

    private final SqlSession session;
    private final Clock clock;

    public StockInternalReconcile(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> closeWindow(String enterpriseId, String warehouseId, String cutoffId, Timestamp closedAt,
            String sourceWatermark, String postingWatermark, String receiptWatermark) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, cutoffId);
        if (closedAt == null) {
            throw new JobRunException("INVALID_CUTOFF", "对账必须提供稳定关闭时刻");
        }
        // 传入的字符串只是窗口引用，不能替代服务端三方证明；新窗口默认未验证。
        int complete = 0;
        Timestamp now = Timestamp.from(clock.instant());
        session.getMapper(ReconciliationMapper.class).upsertCutoff(UUID.randomUUID().toString(), enterpriseId,
                warehouseId, cutoffId, closedAt, blankToNull(sourceWatermark), blankToNull(postingWatermark),
                blankToNull(receiptWatermark), complete, now);
        Map<String, Object> existing = session.getMapper(ReconciliationMapper.class).lockCutoff(enterpriseId, warehouseId, cutoffId);
        if (!closedAt.equals(timestampOf(existing.get("closed_at")))
                || !java.util.Objects.equals(blankToNull(sourceWatermark), existing.get("source_watermark"))
                || !java.util.Objects.equals(blankToNull(postingWatermark), existing.get("posting_watermark"))
                || !java.util.Objects.equals(blankToNull(receiptWatermark), existing.get("receipt_watermark"))) {
            throw new JobRunException("CUTOFF_CONFLICT", "窗口时刻和水位不可覆盖，修订必须使用新窗口");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cutoffId", cutoffId);
        body.put("watermarksComplete", asInt(existing.get("history_frozen")) == 1 && asInt(existing.get("evidence_version")) == 1 && asInt(existing.get("watermarks_complete")) == 1);
        return body;
    }

    public void ingestSourceFact(String enterpriseId, String warehouseId, String sourceService, String commandId,
            String effectKey, String factKind, BigDecimal quantity, Timestamp occurredAt, String watermark) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, commandId);
        if (!"PHYSICAL".equals(factKind) && !"POSTED".equals(factKind)) {
            throw new JobRunException("INVALID_FACT", "来源事实必须是 PHYSICAL 或 POSTED");
        }
        session.getMapper(ReconciliationMapper.class).insertFactIgnore(UUID.randomUUID().toString(), enterpriseId,
                warehouseId, sourceService, commandId, effectKey, factKind, quantity, occurredAt, watermark,
                Timestamp.from(clock.instant()));
    }

    /** 每次最多各 100 个余额/来源/过账；检查点和差异原子提交，多执行器由窗口行锁串行化。 */
    public Report execute(String enterpriseId, String warehouseId, String cutoffId) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, cutoffId);
        try {
            if (session.getConnection().getTransactionIsolation() != java.sql.Connection.TRANSACTION_REPEATABLE_READ) {
                throw new JobRunException("RECON_ISOLATION_REQUIRED", "单页对账必须使用 REPEATABLE READ 一致性快照");
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("无法校验对账事务隔离", failure); }
        ReconciliationMapper mapper = session.getMapper(ReconciliationMapper.class);
        Map<String, Object> cutoff = mapper.lockCutoff(enterpriseId, warehouseId, cutoffId);
        if (cutoff == null) throw new JobRunException("CUTOFF_MISSING", "对账窗口不存在");
        Timestamp closedAt = timestampOf(cutoff.get("closed_at"));
        Timestamp now = Timestamp.from(clock.instant());
        mapper.insertScan(enterpriseId, warehouseId, cutoffId, UUID.randomUUID().toString(), now);
        Map<String, Object> scan = mapper.lockScan(enterpriseId, warehouseId, cutoffId);
        Set<String> openKeys = new HashSet<>();
        Set<String> checked = new HashSet<>();
        checked.add(WATERMARK + "/" + cutoffId);
        int opened = 0;
        boolean completeWatermarks = asInt(cutoff.get("history_frozen")) == 1 && asInt(cutoff.get("evidence_version")) == 1 && asInt(cutoff.get("watermarks_complete")) == 1;
        if (!completeWatermarks) opened += open(mapper, enterpriseId, warehouseId, cutoffId, WATERMARK, SOURCE_INCOMPLETE,
                cutoffId, null, BigDecimal.ONE, BigDecimal.ZERO, "source/posting/receipt watermark incomplete", now, openKeys);
        List<Map<String, Object>> balances = asInt(scan.get("balance_done")) == 1 ? List.of()
                : mapper.balancePage(enterpriseId, warehouseId, closedAt, string(scan.get("balance_cursor")), PAGE_LIMIT + 1);
        for (Map<String, Object> balance : balances.stream().limit(PAGE_LIMIT).toList()) {
            String id = string(balance.get("id")), sku = string(balance.get("sku_id"));
            BigDecimal onHand = decimal(balance.get("on_hand_qty"));
            checked.add(BALANCE_LEDGER + "/" + id); checked.add(RESERVED_MISMATCH + "/" + id);
            if (onHand.compareTo(decimal(balance.get("ledger_qty"))) != 0) opened += open(mapper, enterpriseId, warehouseId,
                    cutoffId, BALANCE_LEDGER, QTY_MISMATCH, id, sku, decimal(balance.get("ledger_qty")), onHand,
                    "page snapshot: on_hand != latest ledger", now, openKeys);
            if (decimal(balance.get("reserved_qty")).compareTo(decimal(balance.get("remaining_qty"))) != 0) opened += open(mapper,
                    enterpriseId, warehouseId, cutoffId, RESERVED_MISMATCH, QTY_MISMATCH, id, sku,
                    decimal(balance.get("remaining_qty")), decimal(balance.get("reserved_qty")), "page snapshot: reserved != remaining", now, openKeys);
            if (asInt(balance.get("serial_enabled")) == 1) {
                checked.add(SERIAL_QTY + "/" + id);
                if (onHand.compareTo(decimal(balance.get("serial_count"))) != 0) opened += open(mapper, enterpriseId, warehouseId,
                        cutoffId, SERIAL_QTY, SERIAL_CONFLICT, id, sku, decimal(balance.get("serial_count")), onHand,
                        "page snapshot: serial count != on_hand", now, openKeys);
            }
        }
        List<Map<String, Object>> facts = !completeWatermarks || asInt(scan.get("fact_done")) == 1 ? List.of()
                : mapper.factPage(enterpriseId, warehouseId, closedAt, string(scan.get("fact_cursor")), PAGE_LIMIT + 1);
        for (var fact : facts.stream().limit(PAGE_LIMIT).toList()) {
            String scope = sourceScope(fact);
            checked.add(SOURCE_POSTING + "/" + scope);
            if (fact.get("other_id") == null) {
                boolean late = Duration.between(timestampOf(fact.get("occurred_at")).toInstant(), closedAt.toInstant()).compareTo(GRACE) < 0;
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING, late ? LATE_ARRIVAL : MISSING_RIGHT,
                        scope, null, decimal(fact.get("quantity")), BigDecimal.ZERO, "source=" + fact.get("source_service") + "; command=" + fact.get("command_id"), now, openKeys);
            } else if (decimal(fact.get("quantity")).compareTo(decimal(fact.get("other_qty"))) != 0) {
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING, QTY_MISMATCH, scope, null,
                        decimal(fact.get("quantity")), decimal(fact.get("other_qty")), "source qty != posting qty", now, openKeys);
            }
        }
        List<Map<String, Object>> postings = !completeWatermarks || asInt(scan.get("posting_done")) == 1 ? List.of()
                : mapper.postingPage(enterpriseId, warehouseId, closedAt, string(scan.get("posting_cursor")), PAGE_LIMIT + 1);
        for (var posting : postings.stream().limit(PAGE_LIMIT).toList()) {
            String scope = sourceScope(posting);
            // 有对应事实的数量一致性由 fact 流检查；不能只凭存在就把另页的差异关掉。
            if (posting.get("other_id") == null) {
                checked.add(SOURCE_POSTING + "/" + scope);
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING, MISSING_LEFT, scope, null,
                        BigDecimal.ZERO, decimal(posting.get("quantity")), "source=" + posting.get("source_service") + "; command=" + posting.get("command_id"), now, openKeys);
            }
        }
        int closed = closeRepaired(mapper, enterpriseId, warehouseId, cutoffId, openKeys, checked, now);
        advance(scan, "balance", balances); advance(scan, "fact", facts); advance(scan, "posting", postings);
        boolean completed = asInt(scan.get("balance_done")) == 1 && asInt(scan.get("fact_done")) == 1 && asInt(scan.get("posting_done")) == 1;
        if (completed) {
            for (String type : List.of("balance", "fact", "posting")) { scan.put(type + "_cursor", null); scan.put(type + "_done", 0); }
        }
        if (mapper.checkpoint(enterpriseId, warehouseId, cutoffId, scan, completed, now) != 1) throw new JobRunException("CONFLICT", "对账检查点冲突");
        return new Report(Math.min(balances.size(), PAGE_LIMIT), opened, closed, PAGE_LIMIT, completed, completeWatermarks);
    }

    private static void advance(Map<String, Object> scan, String type, List<Map<String, Object>> rows) {
        if (!rows.isEmpty()) scan.put(type + "_cursor", rows.get(Math.min(rows.size(), PAGE_LIMIT) - 1).get("id"));
        scan.put(type + "_done", rows.size() <= PAGE_LIMIT ? 1 : 0);
    }

    private static String sourceScope(Map<String, Object> row) {
        // 不同服务可使用相同命令号；固定长度组合身份避免差异单互相覆盖。
        return UUID.nameUUIDFromBytes((row.get("source_service") + "\n" + row.get("command_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public List<Map<String, Object>> listCases(String enterpriseId, String warehouseId, String cutoffId) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, cutoffId);
        return session.getMapper(ReconciliationMapper.class).listCases(enterpriseId, warehouseId, cutoffId);
    }

    public Map<String, Object> remediate(String enterpriseId, String warehouseId, String caseId, String action,
            String reason, long expectedVersion, String actor) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, caseId);
        if (reason == null || reason.isBlank()) {
            throw new JobRunException("INVALID_REASON", "审批修复必须写原因");
        }
        ReconciliationMapper mapper = session.getMapper(ReconciliationMapper.class);
        Map<String, Object> row = mapper.lockCase(enterpriseId, warehouseId, caseId);
        if (row == null) {
            throw new JobRunException("CASE_NOT_FOUND", "差异单不存在");
        }
        String from = string(row.get("state"));
        if (!"OPEN".equals(from) && !"INVESTIGATING".equals(from) && !"PENDING_APPROVAL".equals(from)) {
            throw new JobRunException("INVALID_STATE", "当前状态不能审批");
        }
        Timestamp now = Timestamp.from(clock.instant());
        if ("REJECT".equals(action)) {
            if (mapper.casCase(enterpriseId, warehouseId, caseId, from, "REJECTED", expectedVersion, actor, null,
                    now) != 1) {
                throw new JobRunException("CONFLICT", "差异单版本冲突");
            }
            return Map.of("state", "REJECTED", "rewroteBalance", false);
        }
        if (!"APPROVE".equals(action)) {
            throw new JobRunException("INVALID_ACTION", "只接受 APPROVE 或 REJECT");
        }
        String operationId = UUID.randomUUID().toString();
        if (mapper.casCase(enterpriseId, warehouseId, caseId, from, "REMEDIATING", expectedVersion, actor, operationId,
                now) != 1) {
            throw new JobRunException("CONFLICT", "差异单版本冲突");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", "REMEDIATING");
        body.put("operationId", operationId);
        body.put("rewroteBalance", false);
        return body;
    }

    private int open(ReconciliationMapper mapper, String enterpriseId, String warehouseId, String cutoffId,
            String caseType, String code, String scopeId, String skuId, BigDecimal expected, BigDecimal actual,
            String evidence, Timestamp now, Set<String> openKeys) {
        openKeys.add(caseType + "/" + scopeId);
        mapper.insertCaseIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, cutoffId, caseType, code,
                scopeId, skuId, expected, actual, evidence, now);
        return 1;
    }

    private int closeRepaired(ReconciliationMapper mapper, String enterpriseId, String warehouseId, String cutoffId,
            Set<String> openKeys, Set<String> checked, Timestamp now) {
        int closed = 0;
        var scoped = checked.stream().map(key -> { int split = key.indexOf('/'); return Map.of("type", key.substring(0, split), "scope", key.substring(split + 1)); }).toList();
        for (Map<String, Object> row : mapper.listRepairing(enterpriseId, warehouseId, cutoffId, scoped)) {
            String key = string(row.get("case_type")) + "/" + string(row.get("scope_id"));
            if (openKeys.contains(key)) {
                continue;
            }
            if (mapper.casCase(enterpriseId, warehouseId, string(row.get("id")), string(row.get("state")), "CLOSED",
                    asLong(row.get("version")), string(row.get("approved_by")),
                    string(row.get("remediation_operation_id")), now) == 1) {
                closed++;
            }
        }
        return closed;
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        for (Map<String, Object> row : rows) {
            index.put(string(row.get(key)), row);
        }
        return index;
    }

    private static void require(String enterpriseId, String warehouseId, String id) {
        if (blank(enterpriseId) || blank(warehouseId) || blank(id)) {
            throw new JobRunException("INVALID_SCOPE", "对账必须带企业/仓/窗口");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String key(Object left, Object right) {
        return string(left) + "/" + string(right);
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return 0;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static Timestamp timestampOf(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value == null) {
            return null;
        }
        return Timestamp.from(com.lrj.wms.inventory.inventory.domain.ExpiryPolicy.instantOf(value));
    }

    public record Report(int scanned, int opened, int closed, int budget, boolean cycleCompleted, boolean watermarksComplete) {
    }
}
