package com.lrj.wms.inventory.migrate.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 仅供受控迁移适配器调用；表和列参数由允许列表与数据库元数据生成。 */
interface MigrationCopyMapper {
    List<Map<String, Object>> columns(@Param("table") String table);
    List<Map<String, Object>> page(@Param("table") String table,
            @Param("columns") List<WarehouseMigrationStore.Column> columns, @Param("key") String key,
            @Param("watermark") String watermark, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("since") Timestamp since, @Param("cursor") String cursor);
    int upsert(@Param("table") String table, @Param("columns") List<WarehouseMigrationStore.Column> columns,
            @Param("row") Map<String, Object> row, @Param("immutable") boolean immutable);
    long count(@Param("table") String table, @Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);
    Map<String, Object> quantities(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);
}
