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
    private static final List<String> IMMUTABLE_TABLES = List.of("stock_ledger");

    public static final List<String> COPY_TABLES = List.of("warehouse", "location", "location_gate", "lot", "stock_balance",
            "stock_ledger", "reservation", "reservation_line", "outbox_event", "command_dedup", "write_idempotency",
            "stock_effect", "stock_effect_attempt", "stock_command", "stock_posting", "execution_permit",
            "execution_claim", "local_serial", "quality_qualification", "job_run", "job_shard",
            "warehouse_move", "stock_hold", "warehouse_adjustment");

    private final SqlSessionFactory source;
    private final SqlSessionFactory target;

    public WarehouseMigrationStore(DataSource source, DataSource target) {
        this.source = factory(source);
        this.target = factory(target);
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        var config = new Configuration(new Environment("migration", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(30);
        config.setCallSettersOnNulls(true);
        config.addMapper(MigrationCopyMapper.class);
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
                var mapper = session.getMapper(MigrationCopyMapper.class);
                for (Map<String, Object> row : rows) {
                    copied += mapper.upsert(table, columns, row, IMMUTABLE_TABLES.contains(table));
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
            session.commit();
        }
    }

    /** 同一目标事务激活路由和库位；冲突不得伪装成功。 */
    public void activateTarget(String enterpriseId, String warehouseId, long epoch, String targetCell,
            String sourceCell, Timestamp now) {
        try (SqlSession session = target.openSession(false)) {
            var routes = session.getMapper(WarehouseRouteMapper.class);
            Map<String, Object> route = routes.lock(enterpriseId, warehouseId);
            if (route == null || routes.casSwitch(enterpriseId, warehouseId, epoch, "ACTIVE", targetCell, sourceCell,
                    ((Number) route.get("version")).longValue(), now) != 1) {
                throw new InventoryException("VERSION_CONFLICT", "目标仓路由激活冲突");
            }
            routes.markGates(enterpriseId, warehouseId, "OPEN", null, now);
            session.commit();
        }
    }
}
