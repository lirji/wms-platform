package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 履约单、attempt、参与者与启动审计。无自研 decision 表。 */
public interface FulfillmentMapper {
    /** 按来源键幂等插入履约单头。 */
    /** insertOrderIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo,
            @Param("digest") String digest, @Param("status") String status, @Param("strategyVersion") long strategyVersion,
            @Param("now") Timestamp now);

    /** 锁定来源单对应履约单。 */
    /** lockOrderBySource：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrderBySource(@Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo);

    /** 按主键锁定履约单。 */
    /** lockOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** 写入履约行。 */
    /** insertLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("sourceLineId") String sourceLineId,
            @Param("skuId") String skuId, @Param("qty") java.math.BigDecimal qty, @Param("unit") String unit,
            @Param("minDays") int minDays, @Param("now") Timestamp now);

    /** CAS 绑定订单活动 attempt。 */
    /** casActiveAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casActiveAttempt(@Param("enterpriseId") String enterpriseId, @Param("orderId") String orderId,
            @Param("attemptId") String attemptId, @Param("expectedActive") String expectedActive,
            @Param("version") long version, @Param("now") Timestamp now);

    /** 读取履约行，供冻结数量校验。 */
    /** lockLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> lockLines(@Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId);

    /** 插入未绑定XID的attempt。 */
    /** insertAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertAttempt(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("state") String state,
            @Param("deadline") Timestamp deadline, @Param("hash") String hash, @Param("digest") String digest,
            @Param("now") Timestamp now);

    /** 锁定attempt映射行。 */
    /** lockAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockAttempt(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** 领取启动权：仅无主或本执行器重放。租约过期不能单独接管。 */
    /** claimLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int claimLaunch(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("owner") String owner,
            @Param("leaseUntil") Timestamp leaseUntil, @Param("epoch") long epoch, @Param("version") long version,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 绑定XID一次，要求当前启动所有者与代际匹配。 */
    /** bindXid：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindXid(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("xid") String xid,
            @Param("owner") String owner, @Param("epoch") long epoch, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 写入TC观察副本，不改业务放行状态。 */
    /** observeTc：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int observeTc(@Param("enterpriseId") String enterpriseId, @Param("id") String id, @Param("status") String status,
            @Param("evidence") String evidence, @Param("now") Timestamp now);

    /** CAS 推进attempt业务状态。 */
    /** casAttemptState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casAttemptState(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
            @Param("state") String state, @Param("expected") String expected, @Param("now") Timestamp now);

    /** 登记固定参与仓。 */
    /** insertParticipant：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertParticipant(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 锁定固定参与仓清单。 */
    /** lockParticipants：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> lockParticipants(@Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId);

    /** 更新仓级观察状态。 */
    /** observeParticipant：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int observeParticipant(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("warehouseId") String warehouseId, @Param("state") String state,
            @Param("confirmedVersion") Long confirmedVersion, @Param("now") Timestamp now);

    /** 绑定仓级分支身份；已绑定则仅允许同XID/branch/action重放。 */
    /** bindParticipantBranch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindParticipantBranch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("warehouseId") String warehouseId, @Param("xid") String xid, @Param("branchId") long branchId,
            @Param("actionName") String actionName, @Param("reservationId") String reservationId,
            @Param("routeEpoch") long routeEpoch, @Param("branchState") String branchState,
            @Param("now") Timestamp now);

    /** 写入参与仓行数量。 */
    /** insertParticipantLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertParticipantLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("participantId") String participantId, @Param("orderLineId") String orderLineId,
            @Param("skuId") String skuId, @Param("qty") java.math.BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    /** 记录启动代际审计。 */
    /** insertLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLaunch(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("epoch") long epoch, @Param("executorId") String executorId,
            @Param("state") String state, @Param("cleanup") String cleanup, @Param("now") Timestamp now);

    /** 把XID写入对应启动审计行。 */
    /** bindLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindLaunch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch, @Param("executorId") String executorId, @Param("xid") String xid,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 当前代际启动审计。 */
    /** lockLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLaunch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch);

    /** 失联标记：未绑定 attempt 的 CLAIMED 启动改为 UNKNOWN。 */
    /** markLaunchUnknown：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markLaunchUnknown(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch, @Param("state") String state, @Param("cleanup") String cleanup,
            @Param("now") Timestamp now);

    /** 记录已知空 XID，不绑定 attempt。 */
    /** recordEmptyXid：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int recordEmptyXid(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch, @Param("xid") String xid, @Param("state") String state,
            @Param("cleanup") String cleanup, @Param("now") Timestamp now);

    /** 空启动清理。attempt 已绑 XID 的行不会命中。 */
    /** cleanupLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int cleanupLaunch(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("epoch") long epoch, @Param("cleanup") String cleanup, @Param("errorCode") String errorCode,
            @Param("now") Timestamp now);

    /** 已证明无业务分支时提升代际。租约过期单独不足。 */
    /** isolateEmptyLaunch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int isolateEmptyLaunch(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
            @Param("owner") String owner, @Param("leaseUntil") Timestamp leaseUntil, @Param("epoch") long epoch,
            @Param("version") long version, @Param("state") String state, @Param("now") Timestamp now);

    /** 参与仓行，供出库建单/执行授权 Outbox 正文。 */
    /** listParticipantLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listParticipantLines(@Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId);

    /** 已绑 XID、尚未终态放行的 attempt，供观察同步。 */
    /** listOpenBoundAttempts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listOpenBoundAttempts(@Param("enterpriseId") String enterpriseId);

    /** TC 已提交、可补齐 ALLOCATED/Outbox 的 attempt。 */
    /** listReadyBarrierAttempts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<String> listReadyBarrierAttempts(@Param("enterpriseId") String enterpriseId);

    /** 屏障 Outbox 幂等写入。 */
    /** insertOutboxIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOutboxIgnore(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId,
            @Param("eventType") String eventType, @Param("operationId") String operationId,
            @Param("payload") String payload, @Param("now") Timestamp now);

    /** 重复屏障事件读取原正文，避免同身份不同内容被静默吞掉。 */
    Map<String,Object> getBarrierOutbox(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("warehouseId") String warehouseId, @Param("eventType") String eventType);

    /** 核对本 attempt 已写的屏障事件数。 */
    /** countOutbox：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countOutbox(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId);

    /** listOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    /** 兼容内部首屏读取，仍强制页大小边界。 */
    default List<Map<String, Object>> listOrders(String enterpriseId, int limit) {
        return listOrdersPage(enterpriseId, com.lrj.wms.runtime.web.CursorPage.parse(limit, null, "internal"))
                .stream().limit(limit).toList();
    }

    /** 同时间戳以主键打破平局，数据库最多读取一页加一条。 */
    List<Map<String, Object>> listOrdersPage(@Param("enterpriseId") String enterpriseId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** listParticipants：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listParticipants(@Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId);
}
