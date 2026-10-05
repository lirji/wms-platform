package com.lrj.wms.inventory.migrate.infrastructure;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 仅供受控迁移适配器调用；表和列参数由允许列表与数据库元数据生成。 */
interface MigrationCopyMapper {
    /** 读取{@code information_schema}，将 SQL 与绑定参数保持在同一持久化入口。 */
    List<Map<String, Object>> columns(@Param("table") String table);

    /** 读取本 Mapper 定义的表，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> page(
            @Param("table") String table,
            @Param("columns") List<WarehouseMigrationStore.Column> columns,
            @Param("key") String key,
            @Param("watermark") String watermark,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("since") Timestamp since,
            @Param("cursor") String cursor);

    /** 在目标当前事务检查同主键归属及复制后的完整行，禁止静默覆盖其他仓。 */
    Map<String, Object> byKey(
            @Param("table") String table,
            @Param("columns") List<WarehouseMigrationStore.Column> columns,
            @Param("key") String key,
            @Param("id") String id);

    /** 写入{@code id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int upsert(
            @Param("table") String table,
            @Param("columns") List<WarehouseMigrationStore.Column> columns,
            @Param("row") Map<String, Object> row,
            @Param("immutable") boolean immutable);

    /** 读取本 Mapper 定义的表，将 SQL 与绑定参数保持在同一持久化入口。 */
    long count(
            @Param("table") String table,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId);

    /** 读取{@code stock_balance}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> quantities(
            @Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    /** Fence没有仓字段，必须通过原意图和终态证据定位，逐意图有界分页。 */
    List<Map<String, Object>> terminalFences(
            @Param("e") String enterprise,
            @Param("w") String warehouse,
            @Param("after") String after);

    /** 读取{@code tcc_fence_log}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> fence(@Param("xid") String xid, @Param("branch") long branch);

    /** 写入{@code tcc_fence_log}、{@code xid}，将 SQL 与绑定参数保持在同一持久化入口。 */
    void insertFence(@Param("f") Map<String, Object> fence);
}
