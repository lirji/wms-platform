package com.lrj.wms.inventory.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 余额、预占、流水与门禁锁。调用方必须带企业/仓条件，空桶由唯一键仲裁。 */
public interface InventoryMapper {
    /** 锁库位门禁，库存写入口第一把锁。 */
    /** lockGate：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockGate(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** 插入空桶；维度冲突时保持原行，调用方必须重新加锁读取。 */
    /** insertBalance：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertBalance(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("now") Timestamp now);

    /** 按完整桶维度加锁。 */
    /** lockBalanceByDimension：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockBalanceByDimension(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode);

    /** 按桶标识加锁。 */
    /** lockBalanceById：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockBalanceById(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId);

    /**
     * 条件更新三量。影响 0 行是版本冲突或不足；CHECK 拒绝为负或占用超过实物。
     * 预占路径由调用方另加 GOOD 资格，本语句不把未知质量默认为可分配。
     */
    /** casAdjust：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casAdjust(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId, @Param("onHandDelta") BigDecimal onHandDelta,
            @Param("reservedDelta") BigDecimal reservedDelta, @Param("claimDelta") BigDecimal claimDelta,
            @Param("expectedVersion") long expectedVersion, @Param("now") Timestamp now);

    /** 良品可分配预占；不足或非 GOOD 影响 0 行。 */
    /** casReserveGood：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReserveGood(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId, @Param("qty") BigDecimal qty, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    /** 写入不可变流水。 */
    /** insertLedger：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLedger(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("operationId") String operationId, @Param("entryNo") int entryNo,
            @Param("balanceId") String balanceId, @Param("onHandDelta") BigDecimal onHandDelta,
            @Param("reservedDelta") BigDecimal reservedDelta, @Param("claimDelta") BigDecimal claimDelta,
            @Param("onHandAfter") BigDecimal onHandAfter, @Param("reservedAfter") BigDecimal reservedAfter,
            @Param("claimAfter") BigDecimal claimAfter, @Param("balanceVersion") long balanceVersion,
            @Param("reasonCode") String reasonCode, @Param("documentId") String documentId, @Param("actorId") String actorId,
            @Param("occurredAt") Timestamp occurredAt, @Param("now") Timestamp now);

    /** 插入仓级预占头；同 attempt 或同 XID 所有者冲突由唯一键拒绝。 */
    /** insertReservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertReservation(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId, @Param("requestDigest") String requestDigest,
            @Param("digestVersion") int digestVersion, @Param("state") String state, @Param("xid") String xid,
            @Param("branchId") long branchId, @Param("actionName") String actionName, @Param("routeEpoch") long routeEpoch,
            @Param("executionAuthorizationId") String executionAuthorizationId, @Param("now") Timestamp now);

    /** 锁预占头。 */
    /** lockReservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockReservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("reservationId") String reservationId);

    /** 插入预占明细。 */
    /** insertReservationLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertReservationLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("reservationId") String reservationId,
            @Param("parentLineId") String parentLineId, @Param("orderLineId") String orderLineId,
            @Param("balanceId") String balanceId, @Param("requestedQty") BigDecimal requestedQty,
            @Param("remainingQty") BigDecimal remainingQty, @Param("pickedQty") BigDecimal pickedQty,
            @Param("consumedQty") BigDecimal consumedQty, @Param("releasedQty") BigDecimal releasedQty,
            @Param("inflightQty") BigDecimal inflightQty, @Param("now") Timestamp now);

    /** 同一 operation 已有流水则幂等重放。 */
    /** countLedger：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLedger(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId);

    /** 预占头 CAS 状态迁移。 */
    /** casReservationState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReservationState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("reservationId") String reservationId, @Param("fromState") String fromState,
            @Param("toState") String toState, @Param("expectedVersion") long expectedVersion, @Param("now") Timestamp now);

    /** 预占涉及的库位，用于先锁门禁。 */
    /** reservationLocationIds：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<String> reservationLocationIds(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);
    /** lockReservationByAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockReservationByAttempt(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);

    /** 无行不锁间隙，避免不同 attempt 并发预占互相堵住。 */
    /** findReservationByAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> findReservationByAttempt(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);

    /** 巡检仍占用的预占，只读，不驱动 Confirm/Cancel。 */
    /** listWatchReservations：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<Map<String, Object>> listWatchReservations(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("limit") int limit);

    /** 读取预占明细（调用方已锁头）。 */
    /** listReservationLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<Map<String, Object>> listReservationLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("reservationId") String reservationId);

    /** TCC Cancel 时把未消费剩余全部释放。 */
    /** casReleaseTriedLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReleaseTriedLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("now") Timestamp now);

    /** 拣货移桶：仍占用源桶的预占明细改挂目标桶。 */
    /** rebindRemainingLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int rebindRemainingLines(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceBalanceId") String sourceBalanceId, @Param("targetBalanceId") String targetBalanceId,
            @Param("now") Timestamp now);

    /** 按预占与源桶锁一条仍有剩余的明细。 */
    /** lockOpenLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOpenLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("reservationId") String reservationId, @Param("balanceId") String balanceId);

    /** 短拣：源行只转走本次 q。 */
    /** casSplitPick：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casSplitPick(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** 发运消费已拣剩余。 */
    /** casConsumePicked：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casConsumePicked(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** 取消未拣：释放当前剩余，不碰已消费。 */
    /** casReleaseRemaining：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReleaseRemaining(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("expected") BigDecimal expected, @Param("now") Timestamp now);
}
