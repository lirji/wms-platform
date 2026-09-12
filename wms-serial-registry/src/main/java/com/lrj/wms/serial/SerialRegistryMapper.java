package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 全局序列号身份。唯一键不含仓。 */
public interface SerialRegistryMapper {
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("state") String state, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId, @Param("bucket") int bucket, @Param("now") Timestamp now);

    /** lockIdentity：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockIdentity(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial);

    /** getIdentity：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getIdentity(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial);

    /** casTransferState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casTransferState(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("transferId") String transferId, @Param("epoch") long epoch, @Param("now") Timestamp now);

    /** casConfirmDestination：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casConfirmDestination(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId,
            @Param("transferId") String transferId, @Param("receiptOp") String receiptOp, @Param("toEpoch") long toEpoch,
            @Param("now") Timestamp now);

    /** activateClaimed：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int activateClaimed(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("state") String state, @Param("now") Timestamp now);

    /** casMissing：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casMissing(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId, @Param("factRef") String factRef,
            @Param("epoch") long epoch, @Param("now") Timestamp now);

    /** casFound：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casFound(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId, @Param("now") Timestamp now);

    /** activateFound：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int activateFound(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("operationId") String operationId, @Param("now") Timestamp now);
    /** 兼容旧节点首次激活未写收货引用，只允许原始认领身份补齐。 */
    int repairActiveReceipt(@Param("enterpriseId") String enterpriseId,@Param("skuId") String skuId,
            @Param("serial") String serial,@Param("warehouseId") String warehouseId,@Param("operationId") String operationId,@Param("now") Timestamp now);
}
