package com.lrj.wms.inventory.recon;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 数量快照读写。完成后禁止改 payload/manifest。 */
public interface SnapshotMapper {
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("scenario") String scenario,
            @Param("cutoffId") String cutoffId, @Param("closedAt") Timestamp closedAt, @Param("digest") String digest,
            @Param("scopeJson") String scopeJson, @Param("watermarks") String watermarks,
            @Param("schemaVersion") int schemaVersion, @Param("now") Timestamp now);

    /** lockByKey：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("scenario") String scenario, @Param("cutoffId") String cutoffId, @Param("digest") String digest);

    /** get：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** casComplete：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casComplete(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("state") String state, @Param("manifest") String manifest,
            @Param("completedAt") Timestamp completedAt, @Param("now") Timestamp now);

    /** insertPartIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPartIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("snapshotId") String snapshotId,
            @Param("partNo") int partNo, @Param("payload") String payload, @Param("rowCount") long rowCount,
            @Param("sha256") String sha256, @Param("now") Timestamp now);

    /** listParts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listParts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("snapshotId") String snapshotId);

    /** listBalances：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
}
