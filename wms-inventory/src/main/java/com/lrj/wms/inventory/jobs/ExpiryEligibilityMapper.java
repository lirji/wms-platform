package com.lrj.wms.inventory.jobs;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 过期批次巡检。只读 lot/reservation，通知行幂等写入。 */
public interface ExpiryEligibilityMapper {
    @Select("SELECT id, expires_at FROM lot WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND expires_at IS NOT NULL AND expires_at<=#{now} ORDER BY id LIMIT #{limit}")
    List<Map<String, Object>> listExpiredLots(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("now") Timestamp now, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM reservation r JOIN reservation_line l "
            + "ON r.enterprise_id=l.enterprise_id AND r.warehouse_id=l.warehouse_id AND r.id=l.reservation_id "
            + "JOIN stock_balance b ON l.enterprise_id=b.enterprise_id AND l.warehouse_id=b.warehouse_id "
            + "AND l.balance_id=b.id "
            + "WHERE r.enterprise_id=#{enterpriseId} AND r.warehouse_id=#{warehouseId} AND b.lot_id=#{lotId} "
            + "AND r.state IN ('TRIED','CONFIRMED') AND l.remaining_qty>0")
    int countOpenReservations(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId);

    @Insert("INSERT INTO expiry_notice (id, enterprise_id, warehouse_id, lot_id, window_id, expires_at, "
            + "open_reservations, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{lotId}, #{windowId}, #{expiresAt}, #{openReservations}, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE open_reservations=VALUES(open_reservations), updated_at=#{now}")
    int upsertNotice(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lotId") String lotId, @Param("windowId") String windowId,
            @Param("expiresAt") Timestamp expiresAt, @Param("openReservations") int openReservations,
            @Param("now") Timestamp now);

    @Select("SELECT COUNT(*) FROM expiry_notice WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND lot_id=#{lotId} AND window_id=#{windowId}")
    int countNotice(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId, @Param("windowId") String windowId);
}
