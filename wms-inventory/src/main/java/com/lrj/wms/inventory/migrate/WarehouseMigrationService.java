package com.lrj.wms.inventory.migrate;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.sql.SQLException;
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
    private final SqlSession source;
    private final com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore copies;
    private final Clock clock;

    public WarehouseMigrationService(SqlSession source, JdbcTemplate sourceJdbc, JdbcTemplate targetJdbc, Clock clock) {
        this.source = source;
        this.copies = new com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore(
                sourceJdbc.getDataSource(), targetJdbc.getDataSource());
        this.clock = clock;
    }

    /**
     * 无路由行或不存在路由表/分片规则时不拦截；已纳入迁移的非 ACTIVE 仓拒绝业务写。
     * 已注册 Mapper 时走 Mapper，避免未纳入分片规则的裸 JDBC 被误判为停写。
     */
    public static void requireWritable(SqlSession session, String enterpriseId, String warehouseId) {
        String state;
        try {
            state = readRouteState(session, enterpriseId, warehouseId);
        } catch (InventoryException error) {
            throw error;
        } catch (Exception error) {
            if (isAbsentRouteControl(error)) {
                return;
            }
            throw new InventoryException("STALE_ROUTE", "读取仓路由失败，请通过服务日志排查");
        }
        if (state == null || state.isBlank() || "null".equals(state)) {
            return;
        }
        if (!ACTIVE.equals(state)) {
            throw new InventoryException("STALE_ROUTE", "仓路由已停写或已切走，拒绝旧库写入");
        }
    }

    private static String readRouteState(SqlSession session, String enterpriseId, String warehouseId)
            throws Exception {
        // 兼容独立内核会话；注册同名 XML，所有路径使用同一套租户条件。
        synchronized (session.getConfiguration()) {
            if (!session.getConfiguration().hasMapper(WarehouseRouteMapper.class)) {
                session.getConfiguration().addMapper(WarehouseRouteMapper.class);
            }
        }
        Map<String, Object> row = session.getMapper(WarehouseRouteMapper.class).get(enterpriseId, warehouseId);
        return row == null ? null : string(row.get("state"));
    }

    static boolean isAbsentRouteControl(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "42S02".equals(sql.getSQLState())) {
                return true;
            }
            String text = String.valueOf(current.getMessage()).toLowerCase();
            if (text.contains("doesn't exist") || text.contains("does not exist") || text.contains("unknown table")
                    || text.contains("cannot find table") || text.contains("can not find table")
                    || text.contains("no table rule") || text.contains("table rule of")) {
                return true;
            }
        }
        return false;
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
        copies.prepareTarget(enterpriseId, warehouseId, targetCell, sourceCell, now);
        return view(routes.get(enterpriseId, warehouseId));
    }

    public Map<String, Object> copyFull(String enterpriseId, String warehouseId) {
        Timestamp cutoff = Timestamp.from(clock.instant());
        int rows = 0;
        for (String table : com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore.COPY_TABLES) {
            rows += copies.copyTable(table, enterpriseId, warehouseId, null);
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
        for (String table : com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore.COPY_TABLES) {
            rows += copies.copyTable(table, enterpriseId, warehouseId, since);
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
        for (String table : com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore.COPY_TABLES) {
            Long sourceCount = copies.count(false, table, enterpriseId, warehouseId);
            Long targetCount = copies.count(true, table, enterpriseId, warehouseId);
            if (sourceCount == null || targetCount == null || !sourceCount.equals(targetCount)) {
                diffs.put(table, Map.of("source", sourceCount, "target", targetCount));
            }
        }
        Map<String, Object> sourceQty = copies.quantities(false, enterpriseId, warehouseId);
        Map<String, Object> targetQty = copies.quantities(true, enterpriseId, warehouseId);
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
        copies.activateTarget(enterpriseId, warehouseId, next, targetCell, sourceCell, now);
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
