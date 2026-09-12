package com.lrj.wms.inventory.count;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 盘点计划与快照。调用方必须带企业/仓条件。 */
public interface CountMapper {
    /** 计划锁之后领取一行，失败退避和租约使其他行仍能取得进展。 */
    Map<String, Object> nextRecovery(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("now") Timestamp now);

    /** 领取本身独立提交，进程崩溃不会无限重置尝试预算。 */
    int claimRecovery(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("epoch") long epoch, @Param("leaseUntil") Timestamp leaseUntil);

    /** 按代际登记失败；已经终态或被接管的行不得被旧执行器覆盖。 */
    int failRecovery(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("epoch") long epoch, @Param("nextAt") Timestamp nextAt,
            @Param("errorCode") String errorCode);

    /** insertPlan：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPlan(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("status") String status, @Param("reason") String reason,
            @Param("now") Timestamp now);

    /** lockPlan：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockPlan(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId);

    /** getPlan：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getPlan(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId);

    /** listPlans：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    /** 兼容内部首屏读取，仍强制页大小边界。 */
    default List<Map<String, Object>> listPlans(String enterpriseId, String warehouseId, int limit) {
        return listPlansPage(enterpriseId, warehouseId, com.lrj.wms.runtime.web.CursorPage.parse(limit, null, "internal"))
                .stream().limit(limit).toList();
    }

    /** 同时间戳以主键打破平局，数据库最多读取一页加一条。 */
    List<Map<String, Object>> listPlansPage(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** casPlanStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casPlanStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("now") Timestamp now);

    /** casApprove：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casApprove(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("approvalId") String approvalId, @Param("actor") String actor,
            @Param("now") Timestamp now);

    /** insertScope：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertScope(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("locationId") String locationId,
            @Param("gateEpoch") long gateEpoch, @Param("now") Timestamp now);

    /** listScope：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listScope(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId);

    /** casGate：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casGate(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("planId") String planId, @Param("reason") String reason, @Param("epochDelta") long epochDelta,
            @Param("now") Timestamp now);

    /** casUnfreeze：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casUnfreeze(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId, @Param("planId") String planId, @Param("now") Timestamp now);

    /** listBalances：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("locationId") String locationId);

    /** countFreeClaims：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countFreeClaims(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** countInflightPermits：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countInflightPermits(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** insertLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("balanceId") String balanceId,
            @Param("locationId") String locationId, @Param("snapshotVersion") long snapshotVersion,
            @Param("snapshotQty") BigDecimal snapshotQty, @Param("reservedQty") BigDecimal reservedQty,
            @Param("status") String status, @Param("now") Timestamp now);

    /** lockLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("lineId") String lineId);

    /** listLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId);

    /** updateCounted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateCounted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("status") String status,
            @Param("now") Timestamp now);

    /** casLineStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casLineStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("fromStatus") String fromStatus, @Param("status") String status,
            @Param("now") Timestamp now);

    /** insertObservationIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertObservationIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("planId") String planId, @Param("lineId") String lineId,
            @Param("observationId") String observationId, @Param("qty") BigDecimal qty, @Param("actorId") String actorId,
            @Param("roundNo") int roundNo, @Param("now") Timestamp now);

    /** lockObservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockObservation(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId);

    /** countObservations：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countObservations(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    /** updateScopeEpoch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateScopeEpoch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("locationId") String locationId, @Param("epoch") long epoch,
            @Param("now") Timestamp now);

    /** scopeEpoch：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Long scopeEpoch(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId, @Param("locationId") String locationId);

    /** insertObservationSerialIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertObservationSerialIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId,
            @Param("serial") String serial, @Param("serialId") String serialId, @Param("presence") String presence,
            @Param("now") Timestamp now);

    /** listObservationSerials：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listObservationSerials(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("observationId") String observationId);

    /** countLineSerials：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLineSerials(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    /** latestObservationId：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    String latestObservationId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);
}
