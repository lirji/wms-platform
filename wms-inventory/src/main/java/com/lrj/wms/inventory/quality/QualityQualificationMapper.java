package com.lrj.wms.inventory.quality;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存质量资格。必须带企业/仓条件。 */
public interface QualityQualificationMapper {
    /** insert：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insert(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("inspectionId") String inspectionId,
            @Param("sourceVersion") long sourceVersion, @Param("skuId") String skuId, @Param("lotId") String lotId,
            @Param("resultCode") String resultCode, @Param("state") String state, @Param("commandId") String commandId,
            @Param("now") Timestamp now);

    /** lockByInspection：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockByInspection(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("inspectionId") String inspectionId);

    /** casNewerVersion：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casNewerVersion(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("inspectionId") String inspectionId, @Param("sourceVersion") long sourceVersion,
            @Param("resultCode") String resultCode, @Param("state") String state, @Param("commandId") String commandId,
            @Param("now") Timestamp now);
}
