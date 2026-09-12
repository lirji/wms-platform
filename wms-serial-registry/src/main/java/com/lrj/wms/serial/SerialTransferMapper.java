package com.lrj.wms.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 序列号转移审计。与身份同事务加锁，唯一键含 transfer。 */
public interface SerialTransferMapper {
    @Insert("INSERT IGNORE INTO serial_transfer (id, enterprise_id, sku_id, normalized_serial, serial_id, transfer_id, "
            + "source_warehouse_id, target_warehouse_id, from_epoch, to_epoch, state, source_release_ref, "
            + "target_receipt_ref, prepare_operation_id, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{skuId}, #{serial}, #{serialId}, #{transferId}, #{sourceWarehouseId}, "
            + "#{targetWarehouseId}, #{fromEpoch}, NULL, #{state}, NULL, NULL, #{operationId}, 0, #{now}, #{now})")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("serialId") String serialId, @Param("transferId") String transferId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("fromEpoch") long fromEpoch, @Param("state") String state, @Param("operationId") String operationId,
            @Param("now") Timestamp now);

    @Select("SELECT id, serial_id, transfer_id, source_warehouse_id, target_warehouse_id, from_epoch, to_epoch, state, "
            + "source_release_ref, target_receipt_ref, prepare_operation_id, version FROM serial_transfer "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} "
            + "AND transfer_id=#{transferId} FOR UPDATE")
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId);

    @Select("SELECT id, serial_id, transfer_id, source_warehouse_id, target_warehouse_id, from_epoch, to_epoch, state, "
            + "source_release_ref, target_receipt_ref, prepare_operation_id, version FROM serial_transfer "
            + "WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} AND normalized_serial=#{serial} "
            + "AND transfer_id=#{transferId}")
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId);

    @Update("UPDATE serial_transfer SET state=#{state}, source_release_ref=#{releaseRef}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND transfer_id=#{transferId} AND state=#{fromState}")
    int casRelease(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("fromState") String fromState,
            @Param("state") String state, @Param("releaseRef") String releaseRef, @Param("now") Timestamp now);

    @Update("UPDATE serial_transfer SET state=#{state}, target_receipt_ref=#{receiptRef}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND transfer_id=#{transferId} AND state=#{fromState}")
    int casReceiving(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("fromState") String fromState,
            @Param("state") String state, @Param("receiptRef") String receiptRef, @Param("now") Timestamp now);

    @Update("UPDATE serial_transfer SET state='COMPLETED', target_receipt_ref=#{receiptRef}, to_epoch=#{toEpoch}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND sku_id=#{skuId} "
            + "AND normalized_serial=#{serial} AND transfer_id=#{transferId} AND state='RECEIVING'")
    int casComplete(@Param("enterpriseId") String enterpriseId, @Param("skuId") String skuId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("receiptRef") String receiptRef,
            @Param("toEpoch") long toEpoch, @Param("now") Timestamp now);
}
