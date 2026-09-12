package com.lrj.wms.inventory.migrate;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仓路由写令牌。无行表示尚未纳入迁移控制。 */
public interface WarehouseRouteMapper {
    @Insert("INSERT INTO warehouse_route (id, enterprise_id, warehouse_id, cell_id, target_cell_id, route_epoch, state, "
            + "cutoff_at, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{cellId}, "
            + "#{targetCellId}, #{epoch}, #{state}, #{cutoff}, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cellId") String cellId,
            @Param("targetCellId") String targetCellId, @Param("epoch") long epoch, @Param("state") String state,
            @Param("cutoff") Timestamp cutoff, @Param("now") Timestamp now);

    @Select("SELECT id, cell_id, target_cell_id, route_epoch, state, cutoff_at, version FROM warehouse_route "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId}")
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    @Select("SELECT id, cell_id, target_cell_id, route_epoch, state, cutoff_at, version FROM warehouse_route "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} FOR UPDATE")
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    @Update("UPDATE warehouse_route SET state=#{state}, target_cell_id=#{targetCellId}, cutoff_at=#{cutoff}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND version=#{expected} AND state=#{fromState}")
    int casState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("fromState") String fromState, @Param("state") String state, @Param("targetCellId") String targetCellId,
            @Param("cutoff") Timestamp cutoff, @Param("expected") long expected, @Param("now") Timestamp now);

    @Update("UPDATE warehouse_route SET route_epoch=#{epoch}, state=#{state}, cell_id=#{cellId}, "
            + "target_cell_id=#{targetCellId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND version=#{expected}")
    int casSwitch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("epoch") long epoch, @Param("state") String state, @Param("cellId") String cellId,
            @Param("targetCellId") String targetCellId, @Param("expected") long expected, @Param("now") Timestamp now);

    @Update("UPDATE location_gate SET state=#{state}, reason_code=#{reason}, fence_epoch=fence_epoch+1, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId}")
    int markGates(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("state") String state, @Param("reason") String reason, @Param("now") Timestamp now);
}
