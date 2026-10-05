package com.lrj.wms.inventory.masterdata.infrastructure;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 主数据 HTTP 按标识读取与写幂等；SQL 只在 XML。 */
public interface MasterdataHttpMapper {
    /** 读取{@code warehouse}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getWarehouse(
            @Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId);

    /** 读取{@code warehouse}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getWarehouseByCode(
            @Param("enterpriseId") String enterpriseId, @Param("code") String code);

    /** 读取{@code sku}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getSku(
            @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId);

    /** 读取{@code sku}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getSkuByCode(
            @Param("enterpriseId") String enterpriseId, @Param("code") String code);

    /** 读取{@code location}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getLocation(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** 读取{@code location}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getLocationByCode(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("code") String code);

    /** 读取{@code location_gate}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getLocationGate(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** 读取{@code lot}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getLot(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId);

    /** 读取{@code sku_unit}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getSkuUnit(
            @Param("enterpriseId") String enterpriseId,
            @Param("skuId") String skuId,
            @Param("unitCode") String unitCode);

    /** 读取{@code write_idempotency}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getIdempotency(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    /** 写入{@code write_idempotency}、{@code updated_at}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insertIdempotency(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId,
            @Param("requestDigest") String requestDigest,
            @Param("resourceId") String resourceId,
            @Param("now") Timestamp now);
}
