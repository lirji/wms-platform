package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            + "transfer_id, receipt_operation_id, route_bucket, version FROM serial_registry "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} FOR UPDATE")
    Map<String, Object> lockIdentity(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial);

    @Select("SELECT id, sku_id, normalized_serial, state, owner_warehouse_id, owner_epoch, claim_operation_id, "
            + "transfer_id, receipt_operation_id, route_bucket, version FROM serial_registry "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial}")
    Map<String, Object> getIdentity(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial);

    @Update("UPDATE serial_registry SET state=#{toState}, transfer_id=#{transferId}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND state=#{fromState} AND owner_epoch=#{epoch}")
    int casTransferState(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("transferId") String transferId, @Param("epoch") long epoch, @Param("now") Timestamp now);

    @Update("UPDATE serial_registry SET state='ACTIVE', owner_warehouse_id=#{warehouseId}, owner_epoch=#{toEpoch}, "
            + "transfer_id=#{transferId}, receipt_operation_id=#{receiptOp}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} "
            + "AND state='RECEIVING' AND transfer_id=#{transferId}")
    int casConfirmDestination(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId,
            @Param("transferId") String transferId, @Param("receiptOp") String receiptOp, @Param("toEpoch") long toEpoch,
            @Param("now") Timestamp now);

    @Update("UPDATE serial_registry SET state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} "
            + "AND state='CLAIMED'")
    int activateClaimed(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("state") String state, @Param("now") Timestamp now);

    @Update("UPDATE serial_registry SET state='MISSING', receipt_operation_id=#{factRef}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND state='ACTIVE' AND owner_warehouse_id=#{warehouseId} "
            + "AND owner_epoch=#{epoch}")
    int casMissing(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId, @Param("factRef") String factRef,
            @Param("epoch") long epoch, @Param("now") Timestamp now);

    @Update("UPDATE serial_registry SET state='FOUND_CLAIMED', owner_warehouse_id=#{warehouseId}, "
            + "owner_epoch=owner_epoch+1, claim_operation_id=#{operationId}, receipt_operation_id=#{operationId}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND state='MISSING'")
    int casFound(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId, @Param("now") Timestamp now);

    @Update("UPDATE serial_registry SET state='ACTIVE', version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} "
            + "AND state='FOUND_CLAIMED' AND claim_operation_id=#{operationId}")
    int activateFound(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("operationId") String operationId, @Param("now") Timestamp now);
}
