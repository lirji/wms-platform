package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 调拨总单、仓级子单、在途行与操作事实。 */
public interface TransferMapper {
    /** insertOrderIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("status") String status, @Param("now") Timestamp now);

    /** lockOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** insertLeg：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLeg(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("warehouseId") String warehouseId, @Param("role") String role,
            @Param("status") String status, @Param("now") Timestamp now);

    /** insertLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("skuId") String skuId,
            @Param("businessLotKey") String businessLotKey, @Param("sourceLotId") String sourceLotId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("now") Timestamp now);

    /** lockLine：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId);

    /** listLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId);

    /** listLegs：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLegs(@Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId);

    /** insertFactIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertFactIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("lineId") String lineId,
            @Param("warehouseId") String warehouseId, @Param("action") String action,
            @Param("operationId") String operationId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** getFact：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getFact(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("action") String action, @Param("operationId") String operationId);

    /** addIssued：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addIssued(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addReceived：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addReceived(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** updateOrderStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("status") String status, @Param("now") Timestamp now);

    /** updateLegStatus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateLegStatus(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("warehouseId") String warehouseId, @Param("status") String status, @Param("now") Timestamp now);

    /** insertAuthIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertAuthIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("lineId") String lineId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** lockAuthByClient：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockAuthByClient(@Param("enterpriseId") String enterpriseId, @Param("lineId") String lineId,
            @Param("clientOperationId") String clientOperationId);

    /** lockAuth：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockAuth(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** addQuota：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** consumeQuota：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int consumeQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** releaseQuota：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int releaseQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** addLoss：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addLoss(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    /** casAuthState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casAuthState(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
            @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("tokenVersion") long tokenVersion, @Param("resultRef") String resultRef, @Param("now") Timestamp now);

    /** bindTargetLot：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindTargetLot(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("targetLotId") String targetLotId, @Param("now") Timestamp now);

    /** listOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    /** 兼容内部首屏读取，仍强制页大小边界。 */
    default List<Map<String, Object>> listOrders(String enterpriseId, int limit) {
        return listOrdersPage(enterpriseId, com.lrj.wms.runtime.web.CursorPage.parse(limit, null, "internal"))
                .stream().limit(limit).toList();
    }

    /** 同时间戳以主键打破平局，数据库最多读取一页加一条。 */
    List<Map<String, Object>> listOrdersPage(@Param("enterpriseId") String enterpriseId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
