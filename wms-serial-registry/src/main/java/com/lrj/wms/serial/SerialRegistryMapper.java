package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 全局序列号身份。唯一键不含仓。 */
public interface SerialRegistryMapper {
    @Insert("INSERT IGNORE INTO serial_registry (id, enterprise_id, sku_id, normalized_serial, state, owner_warehouse_id, "
            + "owner_epoch, claim_operation_id, route_bucket, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{skuId}, #{serial}, #{state}, #{warehouseId}, 1, #{operationId}, #{bucket}, 0, #{now}, "
            + "#{now})")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("state") String state, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId, @Param("bucket") int bucket, @Param("now") Timestamp now);

    @Select("SELECT id, sku_id, normalized_serial, state, owner_warehouse_id, owner_epoch, claim_operation_id, "
            + "route_bucket, version FROM serial_registry WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} FOR UPDATE")
    Map<String, Object> lockIdentity(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial);
}
