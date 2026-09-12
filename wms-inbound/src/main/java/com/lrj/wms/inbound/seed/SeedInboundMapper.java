package com.lrj.wms.inbound.seed;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

/** 入库演示单幂等写入。 */
public interface SeedInboundMapper {
    /** insertOrderIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("externalSource") String externalSource,
            @Param("externalNo") String externalNo, @Param("ownerId") String ownerId, @Param("status") String status,
            @Param("now") Timestamp now);

    /** insertLineIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("externalLineId") String externalLineId, @Param("skuId") String skuId,
            @Param("expectedQty") BigDecimal expectedQty, @Param("receivedPhysical") BigDecimal receivedPhysical,
            @Param("unit") String unit, @Param("now") Timestamp now);

    /** countOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countOrders(@Param("enterpriseId") String enterpriseId);

    /** countLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLines(@Param("enterpriseId") String enterpriseId);
}
