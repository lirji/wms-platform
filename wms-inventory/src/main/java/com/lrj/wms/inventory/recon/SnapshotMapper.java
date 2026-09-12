package com.lrj.wms.inventory.recon;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 数量快照读写。完成后禁止改 payload/manifest。 */
public interface SnapshotMapper {
    @Insert("INSERT INTO reconciliation_snapshot (id, enterprise_id, warehouse_id, scenario_code, cutoff_id, closed_at, "
            + "scope_digest, scope_json, source_watermarks, schema_version, state, manifest_json, completed_at, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{scenario}, #{cutoffId}, "
            + "#{closedAt}, #{digest}, #{scopeJson}, #{watermarks}, #{schemaVersion}, 'EXPORTING', NULL, NULL, 0, "
            + "#{now}, #{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("scenario") String scenario,
            @Param("cutoffId") String cutoffId, @Param("closedAt") Timestamp closedAt, @Param("digest") String digest,
            @Param("scopeJson") String scopeJson, @Param("watermarks") String watermarks,
            @Param("schemaVersion") int schemaVersion, @Param("now") Timestamp now);

    @Select("SELECT * FROM reconciliation_snapshot WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND scenario_code=#{scenario} AND cutoff_id=#{cutoffId} AND scope_digest=#{digest} FOR UPDATE")
    Map<String, Object> lockByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("scenario") String scenario, @Param("cutoffId") String cutoffId, @Param("digest") String digest);

    @Select("SELECT * FROM reconciliation_snapshot WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{id}")
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    @Update("UPDATE reconciliation_snapshot SET state=#{state}, manifest_json=#{manifest}, completed_at=#{completedAt}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{id} AND state='EXPORTING'")
    int casComplete(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("state") String state, @Param("manifest") String manifest,
            @Param("completedAt") Timestamp completedAt, @Param("now") Timestamp now);

    @Insert("INSERT INTO snapshot_part (id, enterprise_id, warehouse_id, snapshot_id, part_no, payload, row_count, "
            + "sha256, state, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{snapshotId}, #{partNo}, #{payload}, #{rowCount}, #{sha256}, 'READY', 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertPartIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("snapshotId") String snapshotId,
            @Param("partNo") int partNo, @Param("payload") String payload, @Param("rowCount") long rowCount,
            @Param("sha256") String sha256, @Param("now") Timestamp now);

    @Select("SELECT part_no, row_count, sha256, payload FROM snapshot_part WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND snapshot_id=#{snapshotId} ORDER BY part_no")
    List<Map<String, Object>> listParts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("snapshotId") String snapshotId);

    @Select("SELECT b.id, b.owner_id, b.sku_id, b.lot_id, b.on_hand_qty, k.base_unit FROM stock_balance b "
            + "JOIN sku k ON k.enterprise_id=b.enterprise_id AND k.id=b.sku_id "
            + "WHERE b.enterprise_id=#{enterpriseId} AND b.warehouse_id=#{warehouseId} AND b.updated_at<#{cutoff} "
            + "ORDER BY b.id LIMIT #{limit}")
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
}
