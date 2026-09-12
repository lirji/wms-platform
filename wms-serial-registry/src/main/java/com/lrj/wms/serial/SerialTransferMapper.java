package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 序列号转移审计。与身份同事务加锁，唯一键含 transfer。 */
public interface SerialTransferMapper {
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("serialId") String serialId, @Param("transferId") String transferId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("fromEpoch") long fromEpoch, @Param("state") String state, @Param("operationId") String operationId,
            @Param("now") Timestamp now);

    /** lock：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId);

    /** get：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId);

    /** casRelease：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casRelease(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("fromState") String fromState,
            @Param("state") String state, @Param("releaseRef") String releaseRef, @Param("now") Timestamp now);

    /** casReceiving：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReceiving(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("fromState") String fromState,
            @Param("state") String state, @Param("receiptRef") String receiptRef, @Param("now") Timestamp now);

    /** casComplete：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casComplete(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("receiptRef") String receiptRef,
            @Param("toEpoch") long toEpoch, @Param("now") Timestamp now);
}
