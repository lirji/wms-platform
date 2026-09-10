package com.lrj.wms.inventory.quality;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存质量资格。必须带企业/仓条件。 */
public interface QualityQualificationMapper {
    @Insert("INSERT INTO quality_qualification (id, enterprise_id, warehouse_id, inspection_id, source_version, sku_id, "
            + "lot_id, result_code, effective_state, command_id, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{inspectionId}, #{sourceVersion}, #{skuId}, #{lotId}, #{resultCode}, "
            + "#{state}, #{commandId}, 0, #{now}, #{now})")
    int insert(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("inspectionId") String inspectionId,
            @Param("sourceVersion") long sourceVersion, @Param("skuId") String skuId, @Param("lotId") String lotId,
            @Param("resultCode") String resultCode, @Param("state") String state, @Param("commandId") String commandId,
            @Param("now") Timestamp now);

    @Select("SELECT id, inspection_id, source_version, sku_id, lot_id, result_code, effective_state, command_id, version "
            + "FROM quality_qualification WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND inspection_id=#{inspectionId} FOR UPDATE")
    Map<String, Object> lockByInspection(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("inspectionId") String inspectionId);

    @Update("UPDATE quality_qualification SET source_version=#{sourceVersion}, result_code=#{resultCode}, "
            + "effective_state=#{state}, command_id=#{commandId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND inspection_id=#{inspectionId} "
            + "AND source_version<#{sourceVersion}")
    int casNewerVersion(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("inspectionId") String inspectionId, @Param("sourceVersion") long sourceVersion,
            @Param("resultCode") String resultCode, @Param("state") String state, @Param("commandId") String commandId,
            @Param("now") Timestamp now);
}
