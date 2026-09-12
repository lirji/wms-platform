package com.lrj.wms.inbound.seed;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 入库演示单幂等写入。 */
public interface SeedInboundMapper {
    @Insert("INSERT IGNORE INTO inbound_order (id, enterprise_id, warehouse_id, external_source, external_no, owner_id, "
            + "status, expected_at, source_version, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{externalSource}, #{externalNo}, #{ownerId}, #{status}, NULL, 0, 0, #{now}, #{now})")
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("externalSource") String externalSource,
            @Param("externalNo") String externalNo, @Param("ownerId") String ownerId, @Param("status") String status,
            @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO inbound_line (id, enterprise_id, warehouse_id, order_id, external_line_id, sku_id, "
            + "expected_qty, received_physical_qty, received_posted_qty, putaway_physical_qty, putaway_posted_qty, "
            + "closed_qty, base_unit, stock_sync_status, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{orderId}, #{externalLineId}, #{skuId}, #{expectedQty}, #{receivedPhysical}, 0, 0, 0, 0, "
            + "#{unit}, 'PENDING', 0, #{now}, #{now})")
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("externalLineId") String externalLineId, @Param("skuId") String skuId,
            @Param("expectedQty") BigDecimal expectedQty, @Param("receivedPhysical") BigDecimal receivedPhysical,
            @Param("unit") String unit, @Param("now") Timestamp now);

    @Select("SELECT COUNT(*) FROM inbound_order WHERE enterprise_id=#{enterpriseId}")
    int countOrders(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM inbound_line WHERE enterprise_id=#{enterpriseId}")
    int countLines(@Param("enterpriseId") String enterpriseId);
}
