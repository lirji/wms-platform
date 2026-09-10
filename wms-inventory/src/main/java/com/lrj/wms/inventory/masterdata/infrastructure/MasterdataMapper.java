package com.lrj.wms.inventory.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** inventory 主数据写入与按作用域读取；调用方必须带企业/仓条件。 */
public interface MasterdataMapper {
    /** 插入仓主数据。 */
    @Insert("INSERT INTO warehouse (id, enterprise_id, warehouse_id, code, name, timezone, state, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{id}, #{code}, #{name}, #{timezone}, #{state}, 0, #{now}, #{now})")
    int insertWarehouse(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("code") String code,
            @Param("name") String name, @Param("timezone") String timezone, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 插入库位主数据。 */
    @Insert("INSERT INTO location (id, enterprise_id, warehouse_id, code, zone_code, location_type, capacity_qty, capacity_unit, "
            + "state, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{code}, #{zoneCode}, "
            + "#{locationType}, #{capacityQty}, #{capacityUnit}, #{state}, 0, #{now}, #{now})")
    int insertLocation(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("code") String code, @Param("zoneCode") String zoneCode,
            @Param("locationType") String locationType, @Param("capacityQty") BigDecimal capacityQty,
            @Param("capacityUnit") String capacityUnit, @Param("state") String state, @Param("now") Timestamp now);

    /** 插入库位门禁，新建库位默认 OPEN 且 fence_epoch=0。 */
    @Insert("INSERT INTO location_gate (id, enterprise_id, warehouse_id, location_id, state, reason_code, fence_epoch, "
            + "count_plan_id, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{locationId}, "
            + "#{state}, #{reasonCode}, #{fenceEpoch}, #{countPlanId}, 0, #{now}, #{now})")
    int insertGate(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("locationId") String locationId, @Param("state") String state,
            @Param("reasonCode") String reasonCode, @Param("fenceEpoch") long fenceEpoch,
            @Param("countPlanId") String countPlanId, @Param("now") Timestamp now);

    /** 插入商品主数据。 */
    @Insert("INSERT INTO sku (id, enterprise_id, code, name, base_unit, quantity_scale, lot_enabled, serial_enabled, "
            + "expiry_enabled, policy_version, state, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{code}, "
            + "#{name}, #{baseUnit}, #{quantityScale}, #{lotEnabled}, #{serialEnabled}, #{expiryEnabled}, #{policyVersion}, "
            + "#{state}, 0, #{now}, #{now})")
    int insertSku(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("code") String code,
            @Param("name") String name, @Param("baseUnit") String baseUnit, @Param("quantityScale") int quantityScale,
            @Param("lotEnabled") int lotEnabled, @Param("serialEnabled") int serialEnabled,
            @Param("expiryEnabled") int expiryEnabled, @Param("policyVersion") long policyVersion,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 插入当前策略版本的单位换算。 */
    @Insert("INSERT INTO sku_unit (id, enterprise_id, sku_id, unit_code, numerator, denominator, policy_version, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{skuId}, #{unitCode}, #{numerator}, #{denominator}, "
            + "#{policyVersion}, 0, #{now}, #{now})")
    int insertSkuUnit(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("unitCode") String unitCode, @Param("numerator") BigDecimal numerator,
            @Param("denominator") BigDecimal denominator, @Param("policyVersion") long policyVersion,
            @Param("now") Timestamp now);

    /** 插入仓级批次；禁止写入 NO_LOT。 */
    @Insert("INSERT INTO lot (id, enterprise_id, warehouse_id, owner_id, sku_id, lot_code, business_lot_key, produced_at, "
            + "expires_at, source_date, expiry_rule_version, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{ownerId}, #{skuId}, #{lotCode}, #{businessLotKey}, #{producedAt}, #{expiresAt}, #{sourceDate}, "
            + "#{expiryRuleVersion}, 0, #{now}, #{now})")
    int insertLot(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("skuId") String skuId,
            @Param("lotCode") String lotCode, @Param("businessLotKey") String businessLotKey,
            @Param("producedAt") Timestamp producedAt, @Param("expiresAt") Timestamp expiresAt,
            @Param("sourceDate") String sourceDate, @Param("expiryRuleVersion") long expiryRuleVersion,
            @Param("now") Timestamp now);

    /** 按企业读取商品精度与开关，找不到返回 null。 */
    @Select("SELECT quantity_scale FROM sku WHERE enterprise_id=#{enterpriseId} AND id=#{skuId}")
    Integer skuQuantityScale(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId);

    /** 读取指定库位门禁状态，找不到返回 null。 */
    @Select("SELECT state FROM location_gate WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND location_id=#{locationId}")
    String gateState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("locationId") String locationId);
}
