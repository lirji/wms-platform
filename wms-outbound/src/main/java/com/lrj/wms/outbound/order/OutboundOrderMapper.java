package com.lrj.wms.outbound.order;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 出库单/行/任务/包裹。必须带企业/仓条件。 */
public interface OutboundOrderMapper {
    /** insertOrderIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId, @Param("ownerId") String ownerId, @Param("authId") String authId,
            @Param("status") String status, @Param("now") Timestamp now);

    /** lockOrderByAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrderByAttempt(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);

    /** lockOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** insertLineIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("orderLineId") String orderLineId, @Param("skuId") String skuId, @Param("qty") BigDecimal qty,
            @Param("unit") String unit, @Param("now") Timestamp now);

    /** lockLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    /** lockLineByOrderLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLineByOrderLine(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("orderLineId") String orderLineId);

    /** updateOrderStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("status") String status, @Param("now") Timestamp now);

    /** addPickedPhysical：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addPickedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addPickedPosted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addPickedPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    /** addPackedPhysical：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addPackedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addShippedPhysical：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addShippedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addShippedPosted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addShippedPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    /** addCancelled：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addCancelled(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** insertTask：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertTask(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType,
            @Param("documentId") String documentId, @Param("lineId") String lineId,
            @Param("sourceLocationId") String sourceLocationId, @Param("targetLocationId") String targetLocationId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("state") String state, @Param("now") Timestamp now);

    /** lockTask：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** claimTask：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int claimTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("workerId") String workerId, @Param("epoch") long epoch,
            @Param("actionId") String actionId, @Param("deviceCommandId") String deviceCommandId,
            @Param("now") Timestamp now);

    /** addTaskCompleted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addTaskCompleted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("qty") BigDecimal qty, @Param("state") String state,
            @Param("now") Timestamp now);

    /** insertPackage：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPackage(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("packageNo") String packageNo, @Param("status") String status, @Param("now") Timestamp now);

    /** insertPackageLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPackageLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("packageId") String packageId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** listOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    /** 兼容内部首屏读取，仍强制页大小边界。 */
    default List<Map<String, Object>> listOrders(String enterpriseId, String warehouseId, int limit) {
        return listOrdersPage(enterpriseId, warehouseId, com.lrj.wms.runtime.web.CursorPage.parse(limit, null, "internal"))
                .stream().limit(limit).toList();
    }

    /** 同时间戳以主键打破平局，数据库最多读取一页加一条。 */
    List<Map<String, Object>> listOrdersPage(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** getOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getOrder(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** listLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);

    /** listTasks：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listTasks(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);
    /** 先定位不可变父单，再按单据→任务顺序加锁，避免规划与执行互相等待。 */
    String taskOrderId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);
    /** 锁住订单后查询规划幂等结果与未完成任务占用，防止重复派工。 */
    Map<String, Object> plannedTaskByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);
    BigDecimal pendingPickQty(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);
    int bindPlanningKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId, @Param("commandId") String commandId);
    int cancelOpenPickTasks(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("now") Timestamp now);
    /** 原PICK回执去重成功后增加本桶发运额度，和来源Inbox同事务。 */
    int addBucketPicked(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("locationId") String locationId, @Param("lotId") String lotId,
            @Param("qty") BigDecimal qty, @Param("now") Timestamp now);
    /** 原SHIP首次受理才消费本桶额度，不能因重放或未知结果重复占用。 */
    int claimBucketShipment(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("locationId") String locationId, @Param("lotId") String lotId,
            @Param("qty") BigDecimal qty, @Param("now") Timestamp now);
    /** 取消回执只能累计到已受理取消量；重放由来源Inbox挡住。 */
    int addCancelledPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);
    /** 全部拣发及取消都同步后才展示POSTED，不能用单条回执掩盖其他待同步动作。 */
    int refreshStockSync(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);
}