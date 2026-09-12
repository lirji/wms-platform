package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 仓内序列号记录。必须带企业/仓条件。 */
public interface LocalSerialMapper {
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("serial") String serial, @Param("skuId") String skuId,
            @Param("lotId") String lotId, @Param("balanceId") String balanceId, @Param("state") String state,
            @Param("operationId") String operationId, @Param("registryState") String registryState,
            @Param("registryError") String registryError, @Param("now") Timestamp now);

    /** lock：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial);

    /** get：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial);

    /** casSeal：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casSeal(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("fromState") String fromState, @Param("state") String state,
            @Param("transferId") String transferId, @Param("releaseRef") String releaseRef,
            @Param("registryState") String registryState, @Param("epoch") long epoch, @Param("now") Timestamp now);

    /** bindTransfer：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bindTransfer(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("now") Timestamp now);

    /** updateState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("balanceId") String balanceId, @Param("state") String state,
            @Param("registryState") String registryState, @Param("registryError") String registryError,
            @Param("epoch") long epoch, @Param("now") Timestamp now);

    /** lockActiveByBalance：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> lockActiveByBalance(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("balanceId") String balanceId);

    /** countMissingPending：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countMissingPending(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("planId") String planId);
}
