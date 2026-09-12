package com.lrj.wms.inventory.count;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 盘点计划与快照。调用方必须带企业/仓条件。 */
public interface CountMapper {
    @Insert("INSERT INTO count_plan (id, enterprise_id, warehouse_id, status, reason_code, scope_version, approved_by, "
            + "approved_at, approval_id, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{status}, #{reason}, 1, NULL, NULL, NULL, 0, #{now}, #{now})")
    int insertPlan(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("status") String status, @Param("reason") String reason,
            @Param("now") Timestamp now);

    @Select("SELECT id, status, reason_code, scope_version, approved_by, approval_id, version FROM count_plan "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{planId} FOR UPDATE")
    Map<String, Object> lockPlan(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId);

    @Select("SELECT id, status, reason_code, scope_version, approved_by, approval_id, version FROM count_plan "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{planId}")
    Map<String, Object> getPlan(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId);

    @Select("SELECT id, status, reason_code, scope_version, approved_by, approval_id, version, created_at "
            + "FROM count_plan WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> listPlans(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("limit") int limit);

    @Update("UPDATE count_plan SET status=#{toStatus}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{planId} AND status=#{fromStatus}")
    int casPlanStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("now") Timestamp now);

    @Update("UPDATE count_plan SET status='APPROVED', approved_by=#{actor}, approved_at=#{now}, approval_id=#{approvalId}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{planId} AND status='REVIEWING'")
    int casApprove(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("approvalId") String approvalId, @Param("actor") String actor,
            @Param("now") Timestamp now);

    @Insert("INSERT INTO count_scope (id, enterprise_id, warehouse_id, count_plan_id, location_id, gate_epoch, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{planId}, #{locationId}, "
            + "#{gateEpoch}, 0, #{now}, #{now})")
    int insertScope(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("locationId") String locationId,
            @Param("gateEpoch") long gateEpoch, @Param("now") Timestamp now);

    @Select("SELECT location_id, gate_epoch FROM count_scope WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND count_plan_id=#{planId} ORDER BY location_id")
    List<Map<String, Object>> listScope(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId);

    @Update("UPDATE location_gate SET state=#{toState}, count_plan_id=#{planId}, reason_code=#{reason}, "
            + "fence_epoch=fence_epoch+#{epochDelta}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND location_id=#{locationId} "
            + "AND state=#{fromState} AND (count_plan_id IS NULL OR count_plan_id=#{planId})")
    int casGate(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("planId") String planId, @Param("reason") String reason, @Param("epochDelta") long epochDelta,
            @Param("now") Timestamp now);

    @Update("UPDATE location_gate SET state='OPEN', count_plan_id=NULL, reason_code=NULL, fence_epoch=fence_epoch+1, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND location_id=#{locationId} AND state='FROZEN' AND count_plan_id=#{planId}")
    int casUnfreeze(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId, @Param("planId") String planId, @Param("now") Timestamp now);

    @Select("SELECT id, location_id, sku_id, lot_id, quality_code, on_hand_qty, reserved_qty, free_execution_claim_qty, "
            + "version FROM stock_balance WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND location_id=#{locationId} ORDER BY id")
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("locationId") String locationId);

    @Select("SELECT COUNT(*) FROM stock_balance WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND location_id=#{locationId} AND free_execution_claim_qty>0")
    int countFreeClaims(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    @Select("SELECT COUNT(*) FROM execution_permit p JOIN execution_claim c ON c.enterprise_id=p.enterprise_id "
            + "AND c.warehouse_id=p.warehouse_id AND c.permit_id=p.permit_id "
            + "JOIN stock_balance b ON b.enterprise_id=c.enterprise_id AND b.warehouse_id=c.warehouse_id AND b.id=c.balance_id "
            + "WHERE p.enterprise_id=#{enterpriseId} AND p.warehouse_id=#{warehouseId} AND b.location_id=#{locationId} "
            + "AND p.state IN ('STARTED','UNKNOWN')")
    int countInflightPermits(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    @Insert("INSERT INTO count_line (id, enterprise_id, warehouse_id, count_plan_id, balance_id, location_id, "
            + "snapshot_version, snapshot_qty, reserved_qty, counted_qty, status, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{planId}, #{balanceId}, #{locationId}, #{snapshotVersion}, "
            + "#{snapshotQty}, #{reservedQty}, NULL, #{status}, 0, #{now}, #{now})")
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("balanceId") String balanceId,
            @Param("locationId") String locationId, @Param("snapshotVersion") long snapshotVersion,
            @Param("snapshotQty") BigDecimal snapshotQty, @Param("reservedQty") BigDecimal reservedQty,
            @Param("status") String status, @Param("now") Timestamp now);

    @Select("SELECT id, balance_id, location_id, snapshot_version, snapshot_qty, reserved_qty, counted_qty, status, version "
            + "FROM count_line WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND count_plan_id=#{planId} AND id=#{lineId} FOR UPDATE")
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("lineId") String lineId);

    @Select("SELECT id, balance_id, location_id, snapshot_version, snapshot_qty, reserved_qty, counted_qty, status, version "
            + "FROM count_line WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND count_plan_id=#{planId} ORDER BY id")
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId);

    @Update("UPDATE count_line SET counted_qty=#{qty}, status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{lineId}")
    int updateCounted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("status") String status,
            @Param("now") Timestamp now);

    @Update("UPDATE count_line SET status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{lineId} AND status=#{fromStatus}")
    int casLineStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("fromStatus") String fromStatus, @Param("status") String status,
            @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO count_observation (id, enterprise_id, warehouse_id, count_plan_id, count_line_id, "
            + "observation_id, qty, actor_id, round_no, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{planId}, #{lineId}, #{observationId}, #{qty}, #{actorId}, #{roundNo}, 0, #{now}, #{now})")
    int insertObservationIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("lineId") String lineId,
            @Param("observationId") String observationId, @Param("qty") BigDecimal qty, @Param("actorId") String actorId,
            @Param("roundNo") int roundNo, @Param("now") Timestamp now);

    @Select("SELECT observation_id, count_line_id, qty, actor_id, round_no FROM count_observation "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND observation_id=#{observationId} "
            + "FOR UPDATE")
    Map<String, Object> lockObservation(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId);

    @Select("SELECT COUNT(*) FROM count_observation WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND count_line_id=#{lineId}")
    int countObservations(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    @Update("UPDATE count_scope SET gate_epoch=#{epoch}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND count_plan_id=#{planId} "
            + "AND location_id=#{locationId}")
    int updateScopeEpoch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("locationId") String locationId, @Param("epoch") long epoch,
            @Param("now") Timestamp now);

    @Select("SELECT gate_epoch FROM count_scope WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND count_plan_id=#{planId} AND location_id=#{locationId}")
    Long scopeEpoch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("locationId") String locationId);

    @Insert("INSERT IGNORE INTO count_observation_serial (id, enterprise_id, warehouse_id, observation_id, "
            + "normalized_serial, serial_id, presence_code, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{observationId}, #{serial}, #{serialId}, #{presence}, 0, #{now}, #{now})")
    int insertObservationSerialIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId,
            @Param("serial") String serial, @Param("serialId") String serialId, @Param("presence") String presence,
            @Param("now") Timestamp now);

    @Select("SELECT normalized_serial, presence_code FROM count_observation_serial WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND observation_id=#{observationId} ORDER BY normalized_serial")
    List<Map<String, Object>> listObservationSerials(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId);

    @Select("SELECT COUNT(*) FROM count_observation_serial s JOIN count_observation o "
            + "ON o.enterprise_id=s.enterprise_id AND o.warehouse_id=s.warehouse_id AND o.observation_id=s.observation_id "
            + "WHERE o.enterprise_id=#{enterpriseId} AND o.warehouse_id=#{warehouseId} AND o.count_line_id=#{lineId}")
    int countLineSerials(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    @Select("SELECT observation_id FROM count_observation WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND count_line_id=#{lineId} ORDER BY round_no DESC, created_at DESC LIMIT 1")
    String latestObservationId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);
}
