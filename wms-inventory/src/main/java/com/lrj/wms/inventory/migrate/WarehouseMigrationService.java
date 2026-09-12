package com.lrj.wms.inventory.migrate;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 短暂停写仓迁移：全量/增量拷贝、停写、校验、切 epoch、旧库拒写。
 * 目标已接受写入后禁止直接切回。
 */
public final class WarehouseMigrationService {
    public static final String ACTIVE = "ACTIVE";
    public static final String COPYING = "COPYING";
    public static final String QUIESCING = "QUIESCING";
    public static final String RETIRED = "RETIRED";
    public static final String REASON_MIGRATION = "WAREHOUSE_MIGRATION";
    private static final List<String> IMMUTABLE_TABLES = List.of("stock_ledger");

    static final List<String> COPY_TABLES = List.of("warehouse", "location", "location_gate", "lot", "stock_balance",
            "stock_ledger", "reservation", "reservation_line", "outbox_event", "command_dedup", "write_idempotency",
            "stock_effect", "stock_effect_attempt", "stock_command", "stock_posting", "execution_permit",
            "execution_claim", "local_serial", "quality_qualification", "job_run", "job_shard");

    private final SqlSession source;
    private final JdbcTemplate sourceJdbc;
    private final JdbcTemplate targetJdbc;
    private final Clock clock;

    public WarehouseMigrationService(SqlSession source, JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc, Clock clock) {
        this.source = source;
        this.sourceJdbc = sourceJdbc;
        this.targetJdbc = targetJdbc;
        this.clock = clock;
    }

    /** 无路由行不拦截；已纳入迁移的非 ACTIVE 仓拒绝业务写。用 JDBC 以免未注册 Mapper 的 IT 崩溃。 */
    public static void requireWritable(SqlSession session, String enterpriseId, String warehouseId) {
        try (var statement = session.getConnection().prepareStatement(
                "SELECT state FROM warehouse_route WHERE enterprise_id=? AND warehouse_id=?")) {
            statement.setString(1, enterpriseId);
            statement.setString(2, warehouseId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                if (!ACTIVE.equals(rows.getString(1))) {
                    throw new InventoryException("STALE_ROUTE", "仓路由已停写或已切走，拒绝旧库写入");
                }
            }
        } catch (InventoryException error) {
            throw error;
        } catch (Exception error) {
            throw new InventoryException("STALE_ROUTE", "读取仓路由失败");
        }
    }

    public Map<String, Object> prepare(String enterpriseId, String warehouseId, String sourceCell, String targetCell) {
        Timestamp now = Timestamp.from(clock.instant());
        WarehouseRouteMapper routes = source.getMapper(WarehouseRouteMapper.class);
        routes.insertIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, sourceCell, targetCell, 1L, ACTIVE,
                null, now);
        Map<String, Object> existing = routes.lock(enterpriseId, warehouseId);
        if (existing == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "仓路由未创建");
        }
        if (!ACTIVE.equals(String.valueOf(existing.get("state")))) {
            throw new InventoryException("STALE_ROUTE", "当前状态不能开始拷贝");
        }
        if (routes.casState(enterpriseId, warehouseId, ACTIVE, ACTIVE, targetCell, null, asLong(existing.get("version")),
                now) != 1 && routes.get(enterpriseId, warehouseId) == null) {
            throw new InventoryException("VERSION_CONFLICT", "准备迁移冲突");
        }
        targetJdbc.update("INSERT INTO warehouse_route (id, enterprise_id, warehouse_id, cell_id, target_cell_id, "
                + "route_epoch, state, cutoff_at, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,0,?,?) "
                + "ON DUPLICATE KEY UPDATE updated_at=updated_at", UUID.randomUUID().toString(), enterpriseId,
                warehouseId, targetCell, sourceCell, 1L, COPYING, now, now, now);
        return view(routes.get(enterpriseId, warehouseId));
    }

    public Map<String, Object> copyFull(String enterpriseId, String warehouseId) {
        Timestamp cutoff = Timestamp.from(clock.instant());
        int rows = 0;
        for (String table : COPY_TABLES) {
            rows += copyTable(table, enterpriseId, warehouseId, null);
        }
        stampCutoff(enterpriseId, warehouseId, ACTIVE, cutoff);
        Map<String, Object> body = view(source.getMapper(WarehouseRouteMapper.class).get(enterpriseId, warehouseId));
        body.put("copiedRows", rows);
        body.put("mode", "FULL");
        return body;
    }

    public Map<String, Object> copyIncremental(String enterpriseId, String warehouseId) {
        Map<String, Object> route = requireState(enterpriseId, warehouseId, ACTIVE, QUIESCING);
        Timestamp since = timestampOf(route.get("cutoff_at"));
        Timestamp cutoff = Timestamp.from(clock.instant());
        int rows = 0;
        for (String table : COPY_TABLES) {
            rows += copyTable(table, enterpriseId, warehouseId, since);
        }
        stampCutoff(enterpriseId, warehouseId, String.valueOf(route.get("state")), cutoff);
        Map<String, Object> body = view(source.getMapper(WarehouseRouteMapper.class).get(enterpriseId, warehouseId));
        body.put("copiedRows", rows);
        body.put("mode", "INCREMENTAL");
        return body;
    }

    public Map<String, Object> quiesce(String enterpriseId, String warehouseId) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> route = requireState(enterpriseId, warehouseId, ACTIVE);
        WarehouseRouteMapper routes = source.getMapper(WarehouseRouteMapper.class);
        if (routes.casState(enterpriseId, warehouseId, ACTIVE, QUIESCING, string(route.get("target_cell_id")),
                timestampOf(route.get("cutoff_at")), asLong(route.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "停写冲突");
        }
        routes.markGates(enterpriseId, warehouseId, MasterdataCodes.GATE_MAINTENANCE, REASON_MIGRATION, now);
        return view(routes.get(enterpriseId, warehouseId));
    }

    public Map<String, Object> validate(String enterpriseId, String warehouseId) {
        Map<String, Object> diffs = new LinkedHashMap<>();
        for (String table : COPY_TABLES) {
            Integer sourceCount = sourceJdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE enterprise_id=? AND warehouse_id=?", Integer.class,
                    enterpriseId, warehouseId);
            Integer targetCount = targetJdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE enterprise_id=? AND warehouse_id=?", Integer.class,
                    enterpriseId, warehouseId);
            if (sourceCount == null || targetCount == null || !sourceCount.equals(targetCount)) {
                diffs.put(table, Map.of("source", sourceCount, "target", targetCount));
            }
        }
        Map<String, Object> sourceQty = sourceJdbc.queryForMap(
                "SELECT COALESCE(SUM(on_hand_qty),0) on_hand, COALESCE(SUM(reserved_qty),0) reserved, "
                        + "COALESCE(SUM(free_execution_claim_qty),0) claimed FROM stock_balance "
                        + "WHERE enterprise_id=? AND warehouse_id=?",
                enterpriseId, warehouseId);
        Map<String, Object> targetQty = targetJdbc.queryForMap(
                "SELECT COALESCE(SUM(on_hand_qty),0) on_hand, COALESCE(SUM(reserved_qty),0) reserved, "
                        + "COALESCE(SUM(free_execution_claim_qty),0) claimed FROM stock_balance "
                        + "WHERE enterprise_id=? AND warehouse_id=?",
                enterpriseId, warehouseId);
        if (quantityMismatch(sourceQty, targetQty)) {
            diffs.put("stock_balance_qty", Map.of("source", sourceQty, "target", targetQty));
        }
        if (!diffs.isEmpty()) {
            throw new InventoryException("MIGRATION_MISMATCH", "迁移校验未对齐：" + diffs);
        }
        Map<String, Object> body = view(source.getMapper(WarehouseRouteMapper.class).get(enterpriseId, warehouseId));
        body.put("validated", true);
        return body;
    }

    public Map<String, Object> switchEpoch(String enterpriseId, String warehouseId) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> route = requireState(enterpriseId, warehouseId, QUIESCING);
        long next = asLong(route.get("route_epoch")) + 1;
        String targetCell = string(route.get("target_cell_id"));
        String sourceCell = string(route.get("cell_id"));
        WarehouseRouteMapper routes = source.getMapper(WarehouseRouteMapper.class);
        if (routes.casSwitch(enterpriseId, warehouseId, next, RETIRED, sourceCell, targetCell,
                asLong(route.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "切 epoch 冲突");
        }
        targetJdbc.update("UPDATE warehouse_route SET route_epoch=?, state=?, cell_id=?, target_cell_id=?, "
                + "version=version+1, updated_at=? WHERE enterprise_id=? AND warehouse_id=?", next, ACTIVE, targetCell,
                sourceCell, now, enterpriseId, warehouseId);
        targetJdbc.update("UPDATE location_gate SET state=?, reason_code=NULL, fence_epoch=fence_epoch+1, "
                + "version=version+1, updated_at=? WHERE enterprise_id=? AND warehouse_id=?", MasterdataCodes.GATE_OPEN,
                now, enterpriseId, warehouseId);
        Map<String, Object> body = view(routes.get(enterpriseId, warehouseId));
        body.put("switchedEpoch", next);
        return body;
    }

    public Map<String, Object> abortBeforeSwitch(String enterpriseId, String warehouseId) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> route = requireState(enterpriseId, warehouseId, QUIESCING);
        WarehouseRouteMapper routes = source.getMapper(WarehouseRouteMapper.class);
        if (routes.casState(enterpriseId, warehouseId, String.valueOf(route.get("state")), ACTIVE, null,
                timestampOf(route.get("cutoff_at")), asLong(route.get("version")), now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "回退停写冲突");
        }
        routes.markGates(enterpriseId, warehouseId, MasterdataCodes.GATE_OPEN, null, now);
        return view(routes.get(enterpriseId, warehouseId));
    }

    public Map<String, Object> refuseRollbackAfterCutover(String enterpriseId, String warehouseId) {
        Map<String, Object> route = source.getMapper(WarehouseRouteMapper.class).get(enterpriseId, warehouseId);
        if (route != null && RETIRED.equals(String.valueOf(route.get("state")))) {
            throw new InventoryException("ROLLBACK_FORBIDDEN", "目标已切流，不能直接切回旧库");
        }
        throw new InventoryException("INVALID_STATE", "当前不是已切流状态");
    }

    private Map<String, Object> requireState(String enterpriseId, String warehouseId, String... allowed) {
        Map<String, Object> route = source.getMapper(WarehouseRouteMapper.class).lock(enterpriseId, warehouseId);
        if (route == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "仓路由不存在");
        }
        String state = String.valueOf(route.get("state"));
        for (String item : allowed) {
            if (item.equals(state)) {
                return route;
            }
        }
        throw new InventoryException("STALE_ROUTE", "当前迁移状态不允许该步骤：" + state);
    }

    private void stampCutoff(String enterpriseId, String warehouseId, String expectedState, Timestamp cutoff) {
        Map<String, Object> route = source.getMapper(WarehouseRouteMapper.class).lock(enterpriseId, warehouseId);
        source.getMapper(WarehouseRouteMapper.class).casState(enterpriseId, warehouseId, expectedState, expectedState,
                string(route.get("target_cell_id")), cutoff, asLong(route.get("version")), cutoff);
    }

    private int copyTable(String table, String enterpriseId, String warehouseId, Timestamp since) {
        List<String> columns = writableColumns(table);
        String watermark = watermarkColumn(table, columns);
        String sql = "SELECT " + String.join(",", columns) + " FROM " + table
                + " WHERE enterprise_id=? AND warehouse_id=?";
        List<Map<String, Object>> rows = since == null
                ? sourceJdbc.queryForList(sql, enterpriseId, warehouseId)
                : sourceJdbc.queryForList(sql + " AND " + watermark + ">?", enterpriseId, warehouseId, since);
        int copied = 0;
        for (Map<String, Object> row : rows) {
            String inserts = String.join(",", columns);
            String placeholders = String.join(",", columns.stream()
                    .map(column -> jsonColumn(column) ? "CAST(? AS JSON)" : "?")
                    .toList());
            Object[] values = columns.stream().map(row::get).toArray();
            String insert = "INSERT INTO " + table + " (" + inserts + ") VALUES (" + placeholders + ")";
            if (!IMMUTABLE_TABLES.contains(table)) {
                insert += " AS incoming ON DUPLICATE KEY UPDATE " + String.join(",", columns.stream()
                        .filter(column -> !"id".equalsIgnoreCase(column) && !"event_id".equalsIgnoreCase(column))
                        .map(column -> column + "=incoming." + column)
                        .toList());
            } else {
                insert = "INSERT IGNORE INTO " + table + " (" + inserts + ") VALUES (" + placeholders + ")";
            }
            copied += targetJdbc.update(insert, values);
        }
        return copied;
    }

    private List<String> writableColumns(String table) {
        return sourceJdbc.query(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                        + "AND EXTRA NOT LIKE '%GENERATED%' ORDER BY ORDINAL_POSITION",
                (rs, rowNum) -> rs.getString(1), table);
    }

    private static String watermarkColumn(String table, List<String> columns) {
        if (columns.stream().anyMatch(column -> "updated_at".equalsIgnoreCase(column))) {
            return "updated_at";
        }
        if (columns.stream().anyMatch(column -> "created_at".equalsIgnoreCase(column))) {
            return "created_at";
        }
        throw new InventoryException("MIGRATION_MISMATCH", "表缺少水位列：" + table);
    }

    private static boolean quantityMismatch(Map<String, Object> sourceQty, Map<String, Object> targetQty) {
        return !decimal(sourceQty, "on_hand").equals(decimal(targetQty, "on_hand"))
                || !decimal(sourceQty, "reserved").equals(decimal(targetQty, "reserved"))
                || !decimal(sourceQty, "claimed").equals(decimal(targetQty, "claimed"));
    }

    private static java.math.BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal;
        }
        return new java.math.BigDecimal(String.valueOf(value));
    }

    private static boolean jsonColumn(String column) {
        return "payload".equalsIgnoreCase(column) || column.toLowerCase().endsWith("_json")
                || "watermarks".equalsIgnoreCase(column) || "ledger_manifest".equalsIgnoreCase(column);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (row == null) {
            return body;
        }
        body.put("state", row.get("state"));
        body.put("routeEpoch", asLong(row.get("route_epoch")));
        body.put("cellId", row.get("cell_id"));
        body.put("targetCellId", row.get("target_cell_id"));
        return body;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Timestamp timestampOf(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof java.time.LocalDateTime local) {
            return Timestamp.valueOf(local);
        }
        return null;
    }
}
