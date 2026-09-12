package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仓内序列号记录。必须带企业/仓条件。 */
public interface LocalSerialMapper {
    @Insert("INSERT IGNORE INTO local_serial (id, enterprise_id, warehouse_id, serial_id, sku_id, lot_id, balance_id, "
            + "state, owner_epoch, receipt_operation_id, registry_state, registry_error, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{serial}, #{skuId}, #{lotId}, #{balanceId}, #{state}, 0, "
            + "#{operationId}, #{registryState}, #{registryError}, 0, #{now}, #{now})")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("serial") String serial, @Param("skuId") String skuId,
            @Param("lotId") String lotId, @Param("balanceId") String balanceId, @Param("state") String state,
            @Param("operationId") String operationId, @Param("registryState") String registryState,
            @Param("registryError") String registryError, @Param("now") Timestamp now);

    @Select("SELECT id, serial_id, sku_id, lot_id, balance_id, state, owner_epoch, receipt_operation_id, registry_state, "
            + "registry_error, transfer_id, source_release_ref, version FROM local_serial "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND serial_id=#{serial} FOR UPDATE")
    Map<String, Object> lock(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial);

    @Select("SELECT id, serial_id, sku_id, lot_id, balance_id, state, owner_epoch, receipt_operation_id, registry_state, "
            + "registry_error, transfer_id, source_release_ref, version FROM local_serial "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND serial_id=#{serial}")
    Map<String, Object> get(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial);

    @Update("UPDATE local_serial SET state=#{state}, transfer_id=#{transferId}, source_release_ref=#{releaseRef}, "
            + "registry_state=#{registryState}, owner_epoch=#{epoch}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND serial_id=#{serial} "
            + "AND state=#{fromState} AND owner_epoch=#{epoch}")
    int casSeal(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("fromState") String fromState, @Param("state") String state,
            @Param("transferId") String transferId, @Param("releaseRef") String releaseRef,
            @Param("registryState") String registryState, @Param("epoch") long epoch, @Param("now") Timestamp now);

    @Update("UPDATE local_serial SET transfer_id=#{transferId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND serial_id=#{serial}")
    int bindTransfer(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("transferId") String transferId, @Param("now") Timestamp now);

    @Update("UPDATE local_serial SET balance_id=#{balanceId}, state=#{state}, registry_state=#{registryState}, "
            + "registry_error=#{registryError}, owner_epoch=#{epoch}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND serial_id=#{serial}")
    int updateState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("serial") String serial, @Param("balanceId") String balanceId, @Param("state") String state,
            @Param("registryState") String registryState, @Param("registryError") String registryError,
            @Param("epoch") long epoch, @Param("now") Timestamp now);
}
