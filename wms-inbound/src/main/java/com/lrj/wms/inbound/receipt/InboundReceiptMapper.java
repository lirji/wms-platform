package com.lrj.wms.inbound.receipt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 入库单/行/质检/任务。必须带企业/仓条件。 */
public interface InboundReceiptMapper {
    /** 上架任务维度和已完成量必须锁定，重放不能更改目标库位或原数量。 */
    Map<String, Object> lockPutawayTask(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskId") String taskId);

    /** insertOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrder(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("externalSource") String externalSource,
            @Param("externalNo") String externalNo, @Param("ownerId") String ownerId, @Param("status") String status,
            @Param("now") Timestamp now);

    /** insertLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("externalLineId") String externalLineId, @Param("skuId") String skuId,
            @Param("expectedQty") BigDecimal expectedQty, @Param("unit") String unit, @Param("now") Timestamp now);

    /** lockLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    /** addReceivedPhysical：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addReceivedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addReceivedPosted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addReceivedPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    /** addPutawayPhysical：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addPutawayPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addPutawayPosted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addPutawayPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    /** updateOrderStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("status") String status, @Param("now") Timestamp now);

    /** latestInspection：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> latestInspection(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lineId") String lineId);

    /** insertInspection：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertInspection(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lineId") String lineId,
            @Param("inspected") BigDecimal inspected, @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected, @Param("result") String result,
            @Param("sourceVersion") long sourceVersion, @Param("actorId") String actorId, @Param("now") Timestamp now);

    /** insertTask：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertTask(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType, @Param("orderId") String orderId,
            @Param("lineId") String lineId, @Param("sourceLocationId") String sourceLocationId,
            @Param("targetLocationId") String targetLocationId, @Param("planned") BigDecimal planned,
            @Param("state") String state, @Param("now") Timestamp now);

    /** addTaskCompleted：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addTaskCompleted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId, @Param("qty") BigDecimal qty, @Param("state") String state,
            @Param("now") Timestamp now);

    /** insertPartIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPartIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sessionId") String sessionId, @Param("orderId") String orderId,
            @Param("lineId") String lineId, @Param("partId") String partId, @Param("qty") BigDecimal qty,
            @Param("commandId") String commandId, @Param("actorId") String actorId, @Param("now") Timestamp now);

    /** lockPart：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockPart(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sessionId") String sessionId, @Param("partId") String partId, @Param("lineId") String lineId);

    /** insertObservationIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertObservationIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("deviceId") String deviceId,
            @Param("deviceSessionId") String deviceSessionId, @Param("sequenceNo") long sequenceNo,
            @Param("effectKey") String effectKey, @Param("receiptSessionId") String receiptSessionId,
            @Param("partId") String partId, @Param("lineId") String lineId, @Param("commandId") String commandId,
            @Param("digest") String digest, @Param("state") String state, @Param("now") Timestamp now);

    /** lockObservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("deviceId") String deviceId, @Param("deviceSessionId") String deviceSessionId,
            @Param("sequenceNo") long sequenceNo);

    /** getObservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("deviceId") String deviceId, @Param("deviceSessionId") String deviceSessionId,
            @Param("sequenceNo") long sequenceNo);

    /** bindObservation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("effectKey") String effectKey, @Param("commandId") String commandId,
            @Param("now") Timestamp now);

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
            @Param("orderId") String orderId);

    /** listLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);

    /** listTasks：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listTasks(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);
}
