package com.lrj.wms.inventory.masterdata.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 主数据 HTTP 按标识读取与写幂等；SQL 只在 XML。 */
public interface MasterdataHttpMapper {
    Map<String, Object> getWarehouse(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    Map<String, Object> getWarehouseByCode(@Param("enterpriseId") String enterpriseId, @Param("code") String code);

    Map<String, Object> getSku(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId);

    Map<String, Object> getSkuByCode(@Param("enterpriseId") String enterpriseId, @Param("code") String code);

    Map<String, Object> getLocation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    Map<String, Object> getLocationByCode(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("code") String code);

    Map<String, Object> getLocationGate(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    Map<String, Object> getLot(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId);

    Map<String, Object> getSkuUnit(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("unitCode") String unitCode);

    Map<String, Object> getIdempotency(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    int insertIdempotency(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("requestDigest") String requestDigest, @Param("resourceId") String resourceId,
            @Param("now") Timestamp now);
}
