package com.lrj.wms.inventory.migrate;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 仓路由写令牌。无行表示尚未纳入迁移控制。 */
public interface WarehouseRouteMapper {
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cellId") String cellId,
            @Param("targetCellId") String targetCellId, @Param("epoch") long epoch, @Param("state") String state,
            @Param("cutoff") Timestamp cutoff, @Param("now") Timestamp now);

    /** get：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    /** lock：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    /** casState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("fromState") String fromState, @Param("state") String state, @Param("targetCellId") String targetCellId,
            @Param("cutoff") Timestamp cutoff, @Param("expected") long expected, @Param("now") Timestamp now);

    /** casSwitch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casSwitch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("epoch") long epoch, @Param("state") String state, @Param("cellId") String cellId,
            @Param("targetCellId") String targetCellId, @Param("expected") long expected, @Param("now") Timestamp now);

    /** markGates：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markGates(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("state") String state, @Param("reason") String reason, @Param("now") Timestamp now);
}
