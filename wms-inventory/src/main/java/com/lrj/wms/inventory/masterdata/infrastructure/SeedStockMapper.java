package com.lrj.wms.inventory.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 隔离种子开账库存。复跑不得累加数量，不是业务过账入口。 */
public interface SeedStockMapper {
    /** 首次写入开账余额；维度冲突时保持原数量。 */
    @Insert("INSERT INTO stock_balance (id, enterprise_id, warehouse_id, owner_id, location_id, sku_id, lot_id, quality_code, "
            + "on_hand_qty, reserved_qty, free_execution_claim_qty, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{ownerId}, #{locationId}, #{skuId}, #{lotId}, #{qualityCode}, #{onHand}, "
            + "0, 0, 1, #{now}, #{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertBalanceIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("onHand") BigDecimal onHand, @Param("now") Timestamp now);

    /** 开账流水按操作键去重。 */
    @Insert("INSERT IGNORE INTO stock_ledger (id, enterprise_id, warehouse_id, operation_id, entry_no, balance_id, "
            + "on_hand_delta, reserved_delta, free_execution_claim_delta, on_hand_after, reserved_after, "
            + "free_execution_claim_after, balance_version, reason_code, document_id, actor_id, occurred_at, created_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{operationId}, 1, #{balanceId}, #{onHand}, 0, 0, #{onHand}, "
            + "0, 0, 1, #{reason}, #{documentId}, #{actorId}, #{now}, #{now})")
    int insertLedgerIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("operationId") String operationId,
            @Param("balanceId") String balanceId, @Param("onHand") BigDecimal onHand, @Param("reason") String reason,
            @Param("documentId") String documentId, @Param("actorId") String actorId, @Param("now") Timestamp now);

    /** 把开账余额写入当前投影世代；已有行保持原值。 */
    @Insert("INSERT INTO inventory_view (id, enterprise_id, warehouse_id, generation, owner_id, location_id, sku_id, "
            + "lot_id, quality_code, on_hand_qty, reserved_qty, free_execution_claim_qty, source_version, as_of, "
            + "eligible, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{generation}, "
            + "#{ownerId}, #{locationId}, #{skuId}, #{lotId}, #{qualityCode}, #{onHand}, 0, 0, 1, #{now}, 1, 0, #{now}, "
            + "#{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertViewIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("generation") long generation, @Param("ownerId") String ownerId,
            @Param("locationId") String locationId, @Param("skuId") String skuId, @Param("lotId") String lotId,
            @Param("qualityCode") String qualityCode, @Param("onHand") BigDecimal onHand, @Param("now") Timestamp now);

    @Select("SELECT COUNT(*) FROM stock_balance WHERE enterprise_id=#{enterpriseId}")
    int countBalances(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM stock_ledger WHERE enterprise_id=#{enterpriseId}")
    int countLedgers(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM inventory_view WHERE enterprise_id=#{enterpriseId}")
    int countViews(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM count_plan WHERE enterprise_id=#{enterpriseId}")
    int countPlans(@Param("enterpriseId") String enterpriseId);
}
