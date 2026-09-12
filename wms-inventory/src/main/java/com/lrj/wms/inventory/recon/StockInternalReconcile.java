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
 * 稳定 cutoff 内部对账。缺三方水位不得判丢失；差异落单后不改写余额。
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
        int complete = blank(sourceWatermark) || blank(postingWatermark) || blank(receiptWatermark) ? 0 : 1;
        Timestamp now = Timestamp.from(clock.instant());
        session.getMapper(ReconciliationMapper.class).upsertCutoff(UUID.randomUUID().toString(), enterpriseId,
                warehouseId, cutoffId, closedAt, blankToNull(sourceWatermark), blankToNull(postingWatermark),
                blankToNull(receiptWatermark), complete, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cutoffId", cutoffId);
        body.put("watermarksComplete", complete == 1);
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

    public Report execute(String enterpriseId, String warehouseId, String cutoffId) {
        RootContext.unbind();
        require(enterpriseId, warehouseId, cutoffId);
        ReconciliationMapper mapper = session.getMapper(ReconciliationMapper.class);
        Map<String, Object> cutoff = mapper.lockCutoff(enterpriseId, warehouseId, cutoffId);
        if (cutoff == null) {
            throw new JobRunException("CUTOFF_MISSING", "对账窗口不存在");
        }
        Timestamp closedAt = timestampOf(cutoff.get("closed_at"));
        Timestamp now = Timestamp.from(clock.instant());
        Set<String> openKeys = new HashSet<>();
        int opened = 0;
        if (asInt(cutoff.get("watermarks_complete")) != 1) {
            opened += open(mapper, enterpriseId, warehouseId, cutoffId, WATERMARK, SOURCE_INCOMPLETE, cutoffId, null,
                    BigDecimal.ONE, BigDecimal.ZERO, "source/posting/receipt watermark incomplete", now, openKeys);
        }
        List<Map<String, Object>> balances = mapper.listBalances(enterpriseId, warehouseId, closedAt, PAGE_LIMIT);
        Map<String, Map<String, Object>> ledgers = index(mapper.listLedgerCutoff(enterpriseId, warehouseId, closedAt),
                "balance_id");
        Map<String, Map<String, Object>> reserved = index(mapper.listReservedRemaining(enterpriseId, warehouseId),
                "balance_id");
        Map<String, Map<String, Object>> serials = index(mapper.listSerialCounts(enterpriseId, warehouseId), "balance_id");
        for (Map<String, Object> balance : balances) {
            String balanceId = string(balance.get("id"));
            BigDecimal onHand = decimal(balance.get("on_hand_qty"));
            BigDecimal reservedQty = decimal(balance.get("reserved_qty"));
            Map<String, Object> ledger = ledgers.get(balanceId);
            BigDecimal ledgerOnHand = ledger == null ? BigDecimal.ZERO : decimal(ledger.get("on_hand_after"));
            if (onHand.compareTo(ledgerOnHand) != 0) {
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, BALANCE_LEDGER, QTY_MISMATCH, balanceId,
                        string(balance.get("sku_id")), ledgerOnHand, onHand, "on_hand != closed-window ledger", now,
                        openKeys);
            }
            BigDecimal remaining = reserved.containsKey(balanceId) ? decimal(reserved.get(balanceId).get("remaining_qty"))
                    : BigDecimal.ZERO;
            if (reservedQty.compareTo(remaining) != 0) {
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, RESERVED_MISMATCH, QTY_MISMATCH, balanceId,
                        string(balance.get("sku_id")), remaining, reservedQty, "reserved != open reservation remaining",
                        now, openKeys);
            }
            if (asInt(balance.get("serial_enabled")) == 1) {
                BigDecimal serialCount = serials.containsKey(balanceId)
                        ? decimal(serials.get(balanceId).get("serial_count"))
                        : BigDecimal.ZERO;
                if (onHand.compareTo(serialCount) != 0) {
                    opened += open(mapper, enterpriseId, warehouseId, cutoffId, SERIAL_QTY, SERIAL_CONFLICT, balanceId,
                            string(balance.get("sku_id")), serialCount, onHand, "serial count != on_hand", now, openKeys);
                }
            }
        }
        if (asInt(cutoff.get("watermarks_complete")) == 1) {
            opened += matchSourcePosting(mapper, enterpriseId, warehouseId, cutoffId, closedAt, now, openKeys);
        }
        int closed = closeRepaired(mapper, enterpriseId, warehouseId, cutoffId, openKeys, now);
        return new Report(balances.size(), opened, closed, PAGE_LIMIT);
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

    private int matchSourcePosting(ReconciliationMapper mapper, String enterpriseId, String warehouseId,
            String cutoffId, Timestamp closedAt, Timestamp now, Set<String> openKeys) {
        int opened = 0;
        Map<String, Map<String, Object>> facts = new HashMap<>();
        for (Map<String, Object> fact : mapper.listFacts(enterpriseId, warehouseId, closedAt)) {
            facts.put(key(fact.get("source_service"), fact.get("command_id")), fact);
        }
        Map<String, Map<String, Object>> postings = new HashMap<>();
        for (Map<String, Object> posting : mapper.listPostings(enterpriseId, warehouseId, closedAt)) {
            postings.put(key(posting.get("source_service"), posting.get("command_id")), posting);
        }
        for (Map.Entry<String, Map<String, Object>> entry : facts.entrySet()) {
            Map<String, Object> fact = entry.getValue();
            Map<String, Object> posting = postings.get(entry.getKey());
            String scope = string(fact.get("command_id"));
            if (posting == null) {
                Timestamp occurred = timestampOf(fact.get("occurred_at"));
                boolean late = occurred != null
                        && Duration.between(occurred.toInstant(), closedAt.toInstant()).compareTo(GRACE) < 0;
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING,
                        late ? LATE_ARRIVAL : MISSING_RIGHT, scope, null, decimal(fact.get("quantity")), BigDecimal.ZERO,
                        late ? "source physical within grace" : "source fact missing inventory posting", now, openKeys);
            } else if (decimal(fact.get("quantity")).compareTo(decimal(posting.get("quantity"))) != 0) {
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING, QTY_MISMATCH, scope, null,
                        decimal(fact.get("quantity")), decimal(posting.get("quantity")), "source qty != posting qty",
                        now, openKeys);
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : postings.entrySet()) {
            if (!facts.containsKey(entry.getKey())) {
                opened += open(mapper, enterpriseId, warehouseId, cutoffId, SOURCE_POSTING, MISSING_LEFT,
                        string(entry.getValue().get("command_id")), null, BigDecimal.ZERO,
                        decimal(entry.getValue().get("quantity")), "inventory posting missing source fact", now,
                        openKeys);
            }
        }
        return opened;
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
            Set<String> openKeys, Timestamp now) {
        int closed = 0;
        for (Map<String, Object> row : mapper.listRepairing(enterpriseId, warehouseId, cutoffId)) {
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

    public record Report(int scanned, int opened, int closed, int budget) {
    }
}
