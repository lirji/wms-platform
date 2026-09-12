package com.lrj.wms.inventory.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

/** inventory 主数据写入与按作用域读取；调用方必须带企业/仓条件。 */
public interface MasterdataMapper {
    /** 插入仓主数据。 */
    /** 幂等写入仓；唯一键冲突时保持原行。 */
    /** insertWarehouse：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertWarehouse(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("code") String code,
            @Param("name") String name, @Param("timezone") String timezone, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 插入库位主数据。 */
    /** insertLocation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLocation(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("code") String code, @Param("zoneCode") String zoneCode,
            @Param("locationType") String locationType, @Param("capacityQty") BigDecimal capacityQty,
            @Param("capacityUnit") String capacityUnit, @Param("state") String state, @Param("now") Timestamp now);

    /** 插入库位门禁，新建库位默认 OPEN 且 fence_epoch=0。 */
    /** insertGate：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertGate(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("locationId") String locationId, @Param("state") String state,
            @Param("reasonCode") String reasonCode, @Param("fenceEpoch") long fenceEpoch,
            @Param("countPlanId") String countPlanId, @Param("now") Timestamp now);

    /** 插入商品主数据。 */
    /** insertSku：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertSku(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("code") String code,
            @Param("name") String name, @Param("baseUnit") String baseUnit, @Param("quantityScale") int quantityScale,
            @Param("lotEnabled") int lotEnabled, @Param("serialEnabled") int serialEnabled,
            @Param("expiryEnabled") int expiryEnabled, @Param("policyVersion") long policyVersion,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 插入当前策略版本的单位换算。 */
    /** insertSkuUnit：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertSkuUnit(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("unitCode") String unitCode, @Param("numerator") BigDecimal numerator,
            @Param("denominator") BigDecimal denominator, @Param("policyVersion") long policyVersion,
            @Param("now") Timestamp now);

    /** 插入仓级批次；禁止写入 NO_LOT。 */
    /** insertLot：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLot(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("skuId") String skuId,
            @Param("lotCode") String lotCode, @Param("businessLotKey") String businessLotKey,
            @Param("producedAt") Timestamp producedAt, @Param("expiresAt") Timestamp expiresAt,
            @Param("sourceDate") String sourceDate, @Param("expiryRuleVersion") long expiryRuleVersion,
            @Param("now") Timestamp now);

    /** 按企业读取商品精度与开关，找不到返回 null。 */
    /** skuQuantityScale：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Integer skuQuantityScale(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId);

    /** 读取指定库位门禁状态，找不到返回 null。 */
    /** gateState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    String gateState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);

    /** 幂等写入种子权限映射。 */
    /** insertGrant：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertGrant(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("subject") String subject,
            @Param("warehouseId") String warehouseId, @Param("permission") String permission, @Param("now") Timestamp now);

    /** 统计企业下仓库数，用于种子复跑核对。 */
    /** countWarehouses：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countWarehouses(@Param("enterpriseId") String enterpriseId);

    /** 统计商品数。 */
    /** countSkus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countSkus(@Param("enterpriseId") String enterpriseId);

    /** 统计批次。 */
    /** countLots：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLots(@Param("enterpriseId") String enterpriseId);

    /** 统计授权映射。 */
    /** countGrants：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countGrants(@Param("enterpriseId") String enterpriseId);

    /** 统计单位换算行，用于种子复跑核对。 */
    /** countSkuUnits：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countSkuUnits(@Param("enterpriseId") String enterpriseId);

    /** 企业内是否存在该商品。 */
    /** countSku：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countSku(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId);

    /** 列出企业仓库。 */
    /** listWarehouses：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<java.util.Map<String, Object>> listWarehouses(@Param("enterpriseId") String enterpriseId,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page, @Param("allowed") java.util.Set<String> allowed);

    /** 列出企业商品。 */
    /** listSkus：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<java.util.Map<String, Object>> listSkus(@Param("enterpriseId") String enterpriseId,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** 列出仓内库位。 */
    /** listLocations：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<java.util.Map<String, Object>> listLocations(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** getLocation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.Map<String, Object> getLocation(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("locationId") String locationId);

    /** getLot：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.Map<String, Object> getLot(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lotId") String lotId);

    /** 列出当前策略版本单位换算。 */
    /** listSkuUnits：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<java.util.Map<String, Object>> listSkuUnits(@Param("enterpriseId") String enterpriseId,
            @Param("skuId") String skuId,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** 详情最多内嵌 200 个单位；更多单位通过独立分页接口查询。 */
    default java.util.List<java.util.Map<String, Object>> listSkuUnits(String enterpriseId, String skuId) {
        return listSkuUnits(enterpriseId, skuId, com.lrj.wms.runtime.web.CursorPage.parse(200, null, "units"))
                .stream().limit(200).toList();
    }

    /** 列出仓级批次，含显式效期时刻。 */
    /** listLots：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    java.util.List<java.util.Map<String, Object>> listLots(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
