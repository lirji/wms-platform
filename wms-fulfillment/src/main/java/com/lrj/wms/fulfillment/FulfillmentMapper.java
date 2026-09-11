package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 履约单、attempt、参与者与启动审计。无自研 decision 表。 */
public interface FulfillmentMapper {
    /** 按来源键幂等插入履约单头。 */
    @Insert("INSERT IGNORE INTO fulfillment_order (id, enterprise_id, source_system, source_order_no, request_digest, "
            + "status, strategy_version, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{sourceSystem}, #{sourceOrderNo}, #{digest}, #{status}, #{strategyVersion}, 0, #{now}, #{now})")
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo,
            @Param("digest") String digest, @Param("status") String status, @Param("strategyVersion") long strategyVersion,
            @Param("now") Timestamp now);

    /** 锁定来源单对应履约单。 */
    @Select("SELECT id, source_system, source_order_no, request_digest, status, strategy_version, active_attempt_id, "
            + "version FROM fulfillment_order WHERE enterprise_id=#{enterpriseId} AND source_system=#{sourceSystem} "
            + "AND source_order_no=#{sourceOrderNo} FOR UPDATE")
    Map<String, Object> lockOrderBySource(@Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo);

    /** 按主键锁定履约单。 */
    @Select("SELECT id, source_system, source_order_no, request_digest, status, strategy_version, active_attempt_id, "
            + "version FROM fulfillment_order WHERE enterprise_id=#{enterpriseId} AND id=#{id} FOR UPDATE")
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** 写入履约行。 */
    @Insert("INSERT INTO fulfillment_line (id, enterprise_id, fulfillment_id, source_line_id, sku_id, requested_qty, "
            + "base_unit, min_remaining_days, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{fulfillmentId}, #{sourceLineId}, #{skuId}, #{qty}, #{unit}, #{minDays}, 0, #{now}, #{now})")
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("sourceLineId") String sourceLineId,
            @Param("skuId") String skuId, @Param("qty") java.math.BigDecimal qty, @Param("unit") String unit,
            @Param("minDays") int minDays, @Param("now") Timestamp now);

    /** CAS 绑定订单活动 attempt。 */
    @Update("UPDATE fulfillment_order SET active_attempt_id=#{attemptId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{orderId} AND version=#{version} "
            + "AND (active_attempt_id IS NULL OR active_attempt_id=#{expectedActive})")
    int casActiveAttempt(@Param("enterpriseId") String enterpriseId, @Param("orderId") String orderId,
            @Param("attemptId") String attemptId, @Param("expectedActive") String expectedActive,
            @Param("version") long version, @Param("now") Timestamp now);

    /** 读取履约行，供冻结数量校验。 */
    @Select("SELECT source_line_id, sku_id, requested_qty, base_unit FROM fulfillment_line "
            + "WHERE enterprise_id=#{enterpriseId} AND fulfillment_id=#{fulfillmentId} "
            + "ORDER BY source_line_id FOR UPDATE")
    List<Map<String, Object>> lockLines(@Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId);

    /** 插入未绑定XID的attempt。 */
    @Insert("INSERT INTO allocation_attempt (id, enterprise_id, fulfillment_id, state, deadline, participant_set_hash, "
            + "allocation_digest, cancel_requested, launch_epoch, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{fulfillmentId}, #{state}, #{deadline}, #{hash}, #{digest}, 0, 0, 0, #{now}, #{now})")
    int insertAttempt(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("state") String state,
            @Param("deadline") Timestamp deadline, @Param("hash") String hash, @Param("digest") String digest,
            @Param("now") Timestamp now);

    /** 锁定attempt映射行。 */
    @Select("SELECT id, fulfillment_id, state, deadline, xid, tc_observed_status, tc_terminal_evidence, "
            + "participant_set_hash, allocation_digest, cancel_requested, launch_epoch, launch_owner, "
            + "launch_lease_until, xid_bound_at, version FROM allocation_attempt "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{id} FOR UPDATE")
    Map<String, Object> lockAttempt(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** 领取启动权：无主、本执行器或租约过期才可提升代际。 */
    @Update("UPDATE allocation_attempt SET launch_owner=#{owner}, launch_lease_until=#{leaseUntil}, "
            + "launch_epoch=launch_epoch+1, state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{id} AND xid IS NULL AND launch_epoch=#{epoch} "
            + "AND version=#{version} AND state IN ('PLANNED','TCC_STARTING') "
            + "AND (launch_owner IS NULL OR launch_owner=#{owner} OR launch_lease_until < #{now})")
    int claimLaunch(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("owner") String owner,
            @Param("leaseUntil") Timestamp leaseUntil, @Param("epoch") long epoch, @Param("version") long version,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 绑定XID一次，要求当前启动所有者与代际匹配。 */
    @Update("UPDATE allocation_attempt SET xid=#{xid}, xid_bound_at=#{now}, state=#{state}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND id=#{id} AND xid IS NULL "
            + "AND launch_owner=#{owner} AND launch_epoch=#{epoch} AND state='TCC_STARTING'")
    int bindXid(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("xid") String xid,
            @Param("owner") String owner, @Param("epoch") long epoch, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 写入TC观察副本，不改业务放行状态。 */
    @Update("UPDATE allocation_attempt SET tc_observed_status=#{status}, tc_terminal_evidence=#{evidence}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND id=#{id}")
    int observeTc(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("status") String status,
            @Param("evidence") String evidence, @Param("now") Timestamp now);

    /** CAS 推进attempt业务状态。 */
    @Update("UPDATE allocation_attempt SET state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{id} AND state=#{expected}")
    int casAttemptState(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
            @Param("state") String state, @Param("expected") String expected, @Param("now") Timestamp now);

    /** 登记固定参与仓。 */
    @Insert("INSERT INTO allocation_participant (id, enterprise_id, attempt_id, warehouse_id, state, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{attemptId}, #{warehouseId}, #{state}, 0, "
            + "#{now}, #{now})")
    int insertParticipant(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 锁定固定参与仓清单。 */
    @Select("SELECT id, warehouse_id, reservation_id, xid, branch_id, action_name, route_epoch, observed_branch_state, "
            + "state, last_error, confirmed_version, version "
            + "FROM allocation_participant WHERE enterprise_id=#{enterpriseId} AND attempt_id=#{attemptId} "
            + "ORDER BY warehouse_id FOR UPDATE")
    List<Map<String, Object>> lockParticipants(@Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId);

    /** 更新仓级观察状态。 */
    @Update("UPDATE allocation_participant SET state=#{state}, confirmed_version=#{confirmedVersion}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND attempt_id=#{attemptId} "
            + "AND warehouse_id=#{warehouseId}")
    int observeParticipant(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("warehouseId") String warehouseId, @Param("state") String state,
            @Param("confirmedVersion") Long confirmedVersion, @Param("now") Timestamp now);

    /** 绑定仓级分支身份；已绑定则仅允许同XID/branch/action重放。 */
    @Update("UPDATE allocation_participant SET xid=#{xid}, branch_id=#{branchId}, action_name=#{actionName}, "
            + "reservation_id=#{reservationId}, route_epoch=#{routeEpoch}, observed_branch_state=#{branchState}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND attempt_id=#{attemptId} "
            + "AND warehouse_id=#{warehouseId} "
            + "AND (xid IS NULL OR (xid=#{xid} AND branch_id=#{branchId} AND action_name=#{actionName}))")
    int bindParticipantBranch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("warehouseId") String warehouseId, @Param("xid") String xid, @Param("branchId") long branchId,
            @Param("actionName") String actionName, @Param("reservationId") String reservationId,
            @Param("routeEpoch") long routeEpoch, @Param("branchState") String branchState,
            @Param("now") Timestamp now);

    /** 写入参与仓行数量。 */
    @Insert("INSERT INTO participant_line (id, enterprise_id, participant_id, order_line_id, sku_id, qty, base_unit, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{participantId}, #{orderLineId}, "
            + "#{skuId}, #{qty}, #{unit}, 0, #{now}, #{now})")
    int insertParticipantLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("participantId") String participantId, @Param("orderLineId") String orderLineId,
            @Param("skuId") String skuId, @Param("qty") java.math.BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    /** 记录启动代际审计。 */
    @Insert("INSERT INTO allocation_launch (id, enterprise_id, attempt_id, launch_epoch, executor_id, state, "
            + "cleanup_state, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{attemptId}, "
            + "#{epoch}, #{executorId}, #{state}, #{cleanup}, 0, #{now}, #{now})")
    int insertLaunch(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("epoch") long epoch, @Param("executorId") String executorId,
            @Param("state") String state, @Param("cleanup") String cleanup, @Param("now") Timestamp now);

    /** 把XID写入对应启动审计行。 */
    @Update("UPDATE allocation_launch SET xid=#{xid}, state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND attempt_id=#{attemptId} AND launch_epoch=#{epoch} "
            + "AND executor_id=#{executorId} AND xid IS NULL")
    int bindLaunch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch, @Param("executorId") String executorId, @Param("xid") String xid,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 参与仓行，供出库建单/执行授权 Outbox 正文。 */
    @Select("SELECT p.warehouse_id, p.reservation_id, l.order_line_id, l.sku_id, l.qty, l.base_unit "
            + "FROM allocation_participant p JOIN participant_line l ON l.enterprise_id=p.enterprise_id "
            + "AND l.participant_id=p.id WHERE p.enterprise_id=#{enterpriseId} AND p.attempt_id=#{attemptId} "
            + "ORDER BY p.warehouse_id, l.order_line_id")
    List<Map<String, Object>> listParticipantLines(@Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId);

    /** 已绑 XID、尚未终态放行的 attempt，供观察同步。 */
    @Select("SELECT id, xid, state, tc_observed_status, tc_terminal_evidence FROM allocation_attempt "
            + "WHERE enterprise_id=#{enterpriseId} AND xid IS NOT NULL "
            + "AND state IN ('TCC_TRYING','TCC_COMPLETING') ORDER BY id LIMIT 100")
    List<Map<String, Object>> listOpenBoundAttempts(@Param("enterpriseId") String enterpriseId);

    /** TC 已提交、可补齐 ALLOCATED/Outbox 的 attempt。 */
    @Select("SELECT id FROM allocation_attempt WHERE enterprise_id=#{enterpriseId} "
            + "AND tc_observed_status='Committed' AND tc_terminal_evidence IS NOT NULL "
            + "AND tc_terminal_evidence<>'' AND state IN ('TCC_TRYING','TCC_COMPLETING','ALLOCATED') "
            + "ORDER BY id FOR UPDATE")
    List<String> listReadyBarrierAttempts(@Param("enterpriseId") String enterpriseId);

    /** 屏障 Outbox 幂等写入。 */
    @Insert("INSERT IGNORE INTO fulfillment_outbox (event_id, enterprise_id, attempt_id, warehouse_id, event_type, "
            + "operation_id, payload, status, version, created_at, updated_at) VALUES (#{eventId}, #{enterpriseId}, "
            + "#{attemptId}, #{warehouseId}, #{eventType}, #{operationId}, CAST(#{payload} AS JSON), 'PENDING', 0, "
            + "#{now}, #{now})")
    int insertOutboxIgnore(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId,
            @Param("eventType") String eventType, @Param("operationId") String operationId,
            @Param("payload") String payload, @Param("now") Timestamp now);

    /** 核对本 attempt 已写的屏障事件数。 */
    @Select("SELECT COUNT(*) FROM fulfillment_outbox WHERE enterprise_id=#{enterpriseId} AND attempt_id=#{attemptId}")
    int countOutbox(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId);
}
