package com.lrj.wms.inventory.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 余额、预占、流水与门禁锁。调用方必须带企业/仓条件，空桶由唯一键仲裁。 */
public interface InventoryMapper {
    /** 锁库位门禁，库存写入口第一把锁。 */
    @Select("SELECT id, location_id, state, fence_epoch, count_plan_id, version FROM location_gate "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND location_id=#{locationId} FOR UPDATE")
    Map<String, Object> lockGate(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** 插入空桶；维度冲突时保持原行，调用方必须重新加锁读取。 */
    @Insert("INSERT INTO stock_balance (id, enterprise_id, warehouse_id, owner_id, location_id, sku_id, lot_id, quality_code, "
            + "on_hand_qty, reserved_qty, free_execution_claim_qty, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{ownerId}, #{locationId}, #{skuId}, #{lotId}, #{qualityCode}, 0, 0, 0, 0, "
            + "#{now}, #{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertBalance(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("now") Timestamp now);

    /** 按完整桶维度加锁。 */
    @Select("SELECT id, owner_id, location_id, sku_id, lot_id, quality_code, on_hand_qty, reserved_qty, "
            + "free_execution_claim_qty, version FROM stock_balance WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND owner_id=#{ownerId} AND location_id=#{locationId} AND sku_id=#{skuId} "
            + "AND lot_id=#{lotId} AND quality_code=#{qualityCode} FOR UPDATE")
    Map<String, Object> lockBalanceByDimension(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode);

    /** 按桶标识加锁。 */
    @Select("SELECT id, owner_id, location_id, sku_id, lot_id, quality_code, on_hand_qty, reserved_qty, "
            + "free_execution_claim_qty, version FROM stock_balance WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{balanceId} FOR UPDATE")
    Map<String, Object> lockBalanceById(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId);

    /**
     * 条件更新三量。影响 0 行是版本冲突或不足；CHECK 拒绝为负或占用超过实物。
     * 预占路径由调用方另加 GOOD 资格，本语句不把未知质量默认为可分配。
     */
    @Update("UPDATE stock_balance SET on_hand_qty=on_hand_qty+#{onHandDelta}, reserved_qty=reserved_qty+#{reservedDelta}, "
            + "free_execution_claim_qty=free_execution_claim_qty+#{claimDelta}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{balanceId} "
            + "AND version=#{expectedVersion} "
            + "AND on_hand_qty+#{onHandDelta}>=0 AND reserved_qty+#{reservedDelta}>=0 "
            + "AND free_execution_claim_qty+#{claimDelta}>=0 "
            + "AND on_hand_qty+#{onHandDelta}>=reserved_qty+#{reservedDelta}+free_execution_claim_qty+#{claimDelta}")
    int casAdjust(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId, @Param("onHandDelta") BigDecimal onHandDelta,
            @Param("reservedDelta") BigDecimal reservedDelta, @Param("claimDelta") BigDecimal claimDelta,
            @Param("expectedVersion") long expectedVersion, @Param("now") Timestamp now);

    /** 良品可分配预占；不足或非 GOOD 影响 0 行。 */
    @Update("UPDATE stock_balance SET reserved_qty=reserved_qty+#{qty}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{balanceId} "
            + "AND version=#{expectedVersion} AND quality_code='GOOD' "
            + "AND on_hand_qty-reserved_qty-free_execution_claim_qty>=#{qty}")
    int casReserveGood(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId, @Param("qty") BigDecimal qty, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    /** 写入不可变流水。 */
    @Insert("INSERT INTO stock_ledger (id, enterprise_id, warehouse_id, operation_id, entry_no, balance_id, on_hand_delta, "
            + "reserved_delta, free_execution_claim_delta, on_hand_after, reserved_after, free_execution_claim_after, "
            + "balance_version, reason_code, document_id, actor_id, occurred_at, created_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{operationId}, #{entryNo}, #{balanceId}, #{onHandDelta}, #{reservedDelta}, #{claimDelta}, "
            + "#{onHandAfter}, #{reservedAfter}, #{claimAfter}, #{balanceVersion}, #{reasonCode}, #{documentId}, #{actorId}, "
            + "#{occurredAt}, #{now})")
    int insertLedger(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("operationId") String operationId, @Param("entryNo") int entryNo,
            @Param("balanceId") String balanceId, @Param("onHandDelta") BigDecimal onHandDelta,
            @Param("reservedDelta") BigDecimal reservedDelta, @Param("claimDelta") BigDecimal claimDelta,
            @Param("onHandAfter") BigDecimal onHandAfter, @Param("reservedAfter") BigDecimal reservedAfter,
            @Param("claimAfter") BigDecimal claimAfter, @Param("balanceVersion") long balanceVersion,
            @Param("reasonCode") String reasonCode, @Param("documentId") String documentId, @Param("actorId") String actorId,
            @Param("occurredAt") Timestamp occurredAt, @Param("now") Timestamp now);

    /** 插入仓级预占头；同 attempt 或同 XID 所有者冲突由唯一键拒绝。 */
    @Insert("INSERT INTO reservation (id, enterprise_id, warehouse_id, allocation_id, attempt_id, request_digest, digest_version, "
            + "state, xid, branch_id, action_name, route_epoch, execution_authorization_id, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{allocationId}, #{attemptId}, #{requestDigest}, #{digestVersion}, "
            + "#{state}, #{xid}, #{branchId}, #{actionName}, #{routeEpoch}, #{executionAuthorizationId}, 0, #{now}, #{now})")
    int insertReservation(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId, @Param("requestDigest") String requestDigest,
            @Param("digestVersion") int digestVersion, @Param("state") String state, @Param("xid") String xid,
            @Param("branchId") long branchId, @Param("actionName") String actionName, @Param("routeEpoch") long routeEpoch,
            @Param("executionAuthorizationId") String executionAuthorizationId, @Param("now") Timestamp now);

    /** 锁预占头。 */
    @Select("SELECT id, allocation_id, attempt_id, request_digest, digest_version, state, xid, branch_id, action_name, "
            + "route_epoch, execution_authorization_id, version FROM reservation WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{reservationId} FOR UPDATE")
    Map<String, Object> lockReservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("reservationId") String reservationId);

    /** 插入预占明细。 */
    @Insert("INSERT INTO reservation_line (id, enterprise_id, warehouse_id, reservation_id, parent_line_id, order_line_id, "
            + "balance_id, requested_qty, remaining_qty, picked_qty, consumed_qty, released_qty, inflight_qty, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{reservationId}, #{parentLineId}, "
            + "#{orderLineId}, #{balanceId}, #{requestedQty}, #{remainingQty}, #{pickedQty}, #{consumedQty}, #{releasedQty}, "
            + "#{inflightQty}, 0, #{now}, #{now})")
    int insertReservationLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("reservationId") String reservationId,
            @Param("parentLineId") String parentLineId, @Param("orderLineId") String orderLineId,
            @Param("balanceId") String balanceId, @Param("requestedQty") BigDecimal requestedQty,
            @Param("remainingQty") BigDecimal remainingQty, @Param("pickedQty") BigDecimal pickedQty,
            @Param("consumedQty") BigDecimal consumedQty, @Param("releasedQty") BigDecimal releasedQty,
            @Param("inflightQty") BigDecimal inflightQty, @Param("now") Timestamp now);

    /** 同一 operation 已有流水则幂等重放。 */
    @Select("SELECT COUNT(*) FROM stock_ledger WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND operation_id=#{operationId}")
    int countLedger(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId);

    /** 预占头 CAS 状态迁移。 */
    @Update("UPDATE reservation SET state=#{toState}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{reservationId} "
            + "AND version=#{expectedVersion} AND state=#{fromState}")
    int casReservationState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("reservationId") String reservationId, @Param("fromState") String fromState,
            @Param("toState") String toState, @Param("expectedVersion") long expectedVersion, @Param("now") Timestamp now);

    /** 预占涉及的库位，用于先锁门禁。 */
    @Select("SELECT DISTINCT b.location_id FROM reservation r JOIN reservation_line l "
            + "ON l.enterprise_id=r.enterprise_id AND l.warehouse_id=r.warehouse_id AND l.reservation_id=r.id "
            + "JOIN stock_balance b ON b.enterprise_id=r.enterprise_id AND b.warehouse_id=r.warehouse_id AND b.id=l.balance_id "
            + "WHERE r.enterprise_id=#{enterpriseId} AND r.warehouse_id=#{warehouseId} AND r.allocation_id=#{allocationId} "
            + "AND r.attempt_id=#{attemptId} ORDER BY b.location_id")
    java.util.List<String> reservationLocationIds(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);
    @Select("SELECT id, allocation_id, attempt_id, request_digest, digest_version, state, xid, branch_id, action_name, "
            + "route_epoch, execution_authorization_id, version FROM reservation WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND allocation_id=#{allocationId} AND attempt_id=#{attemptId} FOR UPDATE")
    Map<String, Object> lockReservationByAttempt(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);

    /** 巡检仍占用的预占，只读，不驱动 Confirm/Cancel。 */
    @Select("SELECT id, allocation_id, attempt_id, state, xid, updated_at FROM reservation "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND state IN ('TRIED','CONFIRMED') ORDER BY updated_at, id LIMIT #{limit}")
    java.util.List<Map<String, Object>> listWatchReservations(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("limit") int limit);

    /** 读取预占明细（调用方已锁头）。 */
    @Select("SELECT id, balance_id, requested_qty, remaining_qty, version FROM reservation_line "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND reservation_id=#{reservationId}")
    java.util.List<Map<String, Object>> listReservationLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("reservationId") String reservationId);

    /** TCC Cancel 时把未消费剩余全部释放。 */
    @Update("UPDATE reservation_line SET released_qty=requested_qty, remaining_qty=0, picked_qty=0, inflight_qty=0, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId} AND remaining_qty=requested_qty AND consumed_qty=0 AND released_qty=0")
    int casReleaseTriedLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("now") Timestamp now);

    /** 拣货移桶：仍占用源桶的预占明细改挂目标桶。 */
    @Update("UPDATE reservation_line SET balance_id=#{targetBalanceId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND balance_id=#{sourceBalanceId} "
            + "AND remaining_qty>0")
    int rebindRemainingLines(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceBalanceId") String sourceBalanceId, @Param("targetBalanceId") String targetBalanceId,
            @Param("now") Timestamp now);
}
