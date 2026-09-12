package com.lrj.wms.inventory.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

/** 隔离种子开账库存。复跑不得累加数量，不是业务过账入口。 */
public interface SeedStockMapper {
    /** 首次写入开账余额；维度冲突时保持原数量。 */
    /** insertBalanceIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertBalanceIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("onHand") BigDecimal onHand, @Param("now") Timestamp now);

    /** 开账流水按操作键去重。 */
    /** insertLedgerIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLedgerIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("operationId") String operationId,
            @Param("balanceId") String balanceId, @Param("onHand") BigDecimal onHand, @Param("reason") String reason,
            @Param("documentId") String documentId, @Param("actorId") String actorId, @Param("now") Timestamp now);

    /** 把开账余额写入当前投影世代；已有行保持原值。 */
    /** insertViewIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertViewIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("generation") long generation, @Param("ownerId") String ownerId,
            @Param("locationId") String locationId, @Param("skuId") String skuId, @Param("lotId") String lotId,
            @Param("qualityCode") String qualityCode, @Param("onHand") BigDecimal onHand, @Param("now") Timestamp now);

    /** countBalances：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countBalances(@Param("enterpriseId") String enterpriseId);

    /** countLedgers：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLedgers(@Param("enterpriseId") String enterpriseId);

    /** countViews：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countViews(@Param("enterpriseId") String enterpriseId);

    /** countPlans：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countPlans(@Param("enterpriseId") String enterpriseId);
}
