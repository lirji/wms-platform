package com.lrj.wms.inventory.migrate.infrastructure;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 仓迁移持久化适配器：表名受固定允许列表约束，每批最多 200 行并独立提交目标库。 */
public final class WarehouseMigrationStore {
    private static final List<String> IMMUTABLE_TABLES = List.of("stock_ledger", "message_recovery_audit", "serial_recovery_audit", "archive_plan_item");

    public static final List<String> COPY_TABLES = List.of("warehouse", "location", "location_gate", "lot", "stock_balance",
            "stock_ledger", "reservation", "reservation_line", "outbox_event", "command_dedup", "write_idempotency",
            "stock_effect", "stock_effect_attempt", "stock_command", "stock_posting", "execution_permit",
            "execution_claim", "local_serial", "quality_qualification", "job_run", "job_shard",
            "warehouse_move", "stock_hold", "warehouse_adjustment", "operator_grant",
            "count_plan", "count_scope", "count_line", "count_observation", "count_observation_serial",
            "expiry_notice", "projection_inbox", "inventory_view", "projection_checkpoint",
            "reconciliation_cutoff", "reconciliation_case", "source_execution_fact", "reconciliation_snapshot", "snapshot_part",
            "runtime_message_inbox", "message_recovery_audit", "stock_receipt_quality", "reconciliation_scan",
            "archive_plan", "archive_plan_item", "serial_recovery_intent", "serial_recovery_audit", "inventory_tcc_intent", "serial_receipt_batch", "serial_release_intent", "count_adjustment_intent", "count_serial_intent", "serial_pick_fact");

    private final SqlSessionFactory source;
    private final SqlSessionFactory target;

    public WarehouseMigrationStore(DataSource source, DataSource target) {
        this.source = factory(source);
        this.target = factory(target);
        // 本轮只支持原样搬迁；跨偏移会改变归档候选的原始行摘要，必须另立转换方案。
        String sourceOffset=timeOffset(this.source), targetOffset=timeOffset(this.target);
        if(!sourceOffset.equals(targetOffset)) throw new InventoryException("MIGRATION_TIME_MISMATCH","源目标数据库时间来源不同，禁止按原样搬迁");
    }

    private static String timeOffset(SqlSessionFactory sessions) {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(com.lrj.wms.runtime.db.DatabaseTimeMapper.class);
            var policy=mapper.policy();
            if(policy==null || !mapper.sessionOffset().equals(policy.get("storage_offset")))
                throw new InventoryException("MIGRATION_TIME_MISMATCH","仓迁移要求两库均已核实并登记时间来源");
            return String.valueOf(policy.get("storage_offset"));
        }
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        var config = new Configuration(new Environment("migration", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(30);
        config.setCallSettersOnNulls(true);
        config.addMapper(MigrationCopyMapper.class);
        config.addMapper(com.lrj.wms.runtime.db.DatabaseTimeMapper.class);
        config.addMapper(WarehouseRouteMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    /** 未知表不能到达含动态标识符的 XML。 */
    private static String table(String value) {
        if (!COPY_TABLES.contains(value)) {
            throw new IllegalArgumentException("不允许迁移该表");
        }
        return value;
    }

    /** 列名只接受数据库元数据中的简单标识符，值仍由 JDBC 绑定。 */
    public record Column(String name, boolean json) {
        public Column {
            if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("非法数据库列名");
            }
        }
    }

    /** 每批重新打开事务，避免全仓数据常驻内存；重跑依赖相同主键幂等落库。 */
    public int copyTable(String table, String enterpriseId, String warehouseId, Timestamp since) {
        table(table);
        List<Column> columns;
        try (SqlSession session = source.openSession()) {
            columns = session.getMapper(MigrationCopyMapper.class).columns(table).stream()
                    .map(column -> new Column(String.valueOf(column.get("column_name")),
                            "json".equalsIgnoreCase(String.valueOf(column.get("data_type"))))).toList();
        }
        try(var session=target.openSession()) {
            var targetColumns=session.getMapper(MigrationCopyMapper.class).columns(table);
            if(targetColumns.size()!=columns.size() || !targetColumns.stream().map(c -> new Column(String.valueOf(c.get("column_name")),"json".equalsIgnoreCase(String.valueOf(c.get("data_type"))))).toList().equals(columns))
                throw new InventoryException("MIGRATION_SCHEMA_MISMATCH","源目标表列不一致，先完成兼容迁移");
        }
        String watermark = columns.stream().anyMatch(c -> c.name().equals("updated_at")) ? "updated_at" : "created_at";
        if (columns.stream().noneMatch(c -> c.name().equals(watermark))) {
            throw new InventoryException("MIGRATION_MISMATCH", "迁移表缺少水位列");
        }
        String key = table.equals("outbox_event") ? "event_id" : "id";
        String cursor = null;
        int copied = 0;
        while (true) {
            List<Map<String, Object>> rows;
            try (SqlSession session = source.openSession()) {
                rows = session.getMapper(MigrationCopyMapper.class).page(table, columns, key, watermark,
                        enterpriseId, warehouseId, since, cursor);
            }
            if (rows.isEmpty()) return copied;
            try (SqlSession session = target.openSession(false)) {
                var route=session.getMapper(WarehouseRouteMapper.class).lock(enterpriseId,warehouseId);
                if(route==null || !"COPYING".equals(route.get("state")))
                    throw new InventoryException("MIGRATION_TARGET_IN_USE","目标仓未处于专用复制状态，禁止覆盖");
                var mapper = session.getMapper(MigrationCopyMapper.class);
                for (Map<String, Object> row : rows) {
                    // 全局主键碰撞绝不能把目标其他仓的数据改成本仓；失败回滚当前有界批次。
                    var before=mapper.byKey(table,columns,key,String.valueOf(row.get(key)));
                    if(before!=null && (!enterpriseId.equals(before.get("enterprise_id")) || !warehouseId.equals(before.get("warehouse_id"))))
                        throw new InventoryException("MIGRATION_ID_CONFLICT","目标存在其他范围的同主键记录");
                    copied += mapper.upsert(table, columns, row, IMMUTABLE_TABLES.contains(table));
                    var after=mapper.byKey(table,columns,key,String.valueOf(row.get(key)));
                    if(after==null || !row.equals(after)) throw new InventoryException("MIGRATION_CONTENT_MISMATCH","目标记录与原始数据不一致，拒绝忽略冲突");
                }
                session.commit();
            }
            cursor = String.valueOf(rows.getLast().get(key));
        }
    }

    /** 计数与数量汇总只比较指定企业、仓库。 */
    public long count(boolean onTarget, String table, String enterpriseId, String warehouseId) {
        try (SqlSession session = (onTarget ? target : source).openSession()) {
            return session.getMapper(MigrationCopyMapper.class).count(table(table), enterpriseId, warehouseId);
        }
    }

    public Map<String, Object> quantities(boolean onTarget, String enterpriseId, String warehouseId) {
        try (SqlSession session = (onTarget ? target : source).openSession()) {
            return session.getMapper(MigrationCopyMapper.class).quantities(enterpriseId, warehouseId);
        }
    }

    /** 目标路由幂等创建；尚未激活时不能承接业务写入。 */
    public void prepareTarget(String enterpriseId, String warehouseId, String targetCell, String sourceCell, Timestamp now) {
        try (SqlSession session = target.openSession(false)) {
            session.getMapper(WarehouseRouteMapper.class).insertIgnore(UUID.randomUUID().toString(), enterpriseId,
                    warehouseId, targetCell, sourceCell, 1L, "COPYING", now, now);
            var route=session.getMapper(WarehouseRouteMapper.class).lock(enterpriseId,warehouseId);
            if(route==null || !"COPYING".equals(route.get("state")) || !targetCell.equals(route.get("cell_id")) || !sourceCell.equals(route.get("target_cell_id")))
                throw new InventoryException("MIGRATION_TARGET_IN_USE","目标仓已被使用或属于其他迁移，不自动覆盖");
            session.commit();
        }
    }

    /** 只用于源提交失败后的精确收尾；已激活目标可能已接受新写，不能再用旧源重拷或校验覆盖。 */
    public boolean targetAlreadyActivated(String enterpriseId,String warehouseId,long epoch,String targetCell,String sourceCell) {
        try(var session=target.openSession()) {
            var route=session.getMapper(WarehouseRouteMapper.class).get(enterpriseId,warehouseId);
            return route!=null && "ACTIVE".equals(route.get("state")) && epoch==((Number)route.get("route_epoch")).longValue()
                    && targetCell.equals(route.get("cell_id")) && sourceCell.equals(route.get("target_cell_id"));
        }
    }

    /** 同一目标事务激活路由和库位；冲突不得伪装成功。 */
    public void activateTarget(String enterpriseId, String warehouseId, long epoch, String targetCell,
            String sourceCell, Timestamp now) {
        try (SqlSession session = target.openSession(false)) {
            var routes = session.getMapper(WarehouseRouteMapper.class);
            Map<String, Object> route = routes.lock(enterpriseId, warehouseId);
            // 目标提交而源提交失败时，只承认相同切流的精确重放；不能再次打开目标新产生的门禁。
            if(route!=null && "ACTIVE".equals(route.get("state")) && epoch==((Number)route.get("route_epoch")).longValue()
                    && targetCell.equals(route.get("cell_id")) && sourceCell.equals(route.get("target_cell_id"))) return;
            if(routes.foreignBlockedGates(enterpriseId,warehouseId)>0)
                throw new InventoryException("MIGRATION_GATE_BUSY","目标存在非迁移冻结，禁止切流或擅自开放");
            if (route == null || !"COPYING".equals(route.get("state")) || routes.casSwitch(enterpriseId, warehouseId, epoch, "ACTIVE", targetCell, sourceCell,
                    ((Number) route.get("version")).longValue(), now) != 1) {
                throw new InventoryException("VERSION_CONFLICT", "目标仓路由激活冲突");
            }
            routes.markGates(enterpriseId, warehouseId, "OPEN", null, now);
            session.commit();
        }
    }
}
