package com.lrj.wms.inventory.recon;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 内部对账只读权威库存并落差异单。禁止改写 stock_balance。 */
public interface ReconciliationMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Insert("INSERT INTO reconciliation_cutoff (id, enterprise_id, warehouse_id, cutoff_id, closed_at, "
            + "source_watermark, posting_watermark, receipt_watermark, watermarks_complete, state, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{cutoffId}, #{closedAt}, "
            + "#{sourceWatermark}, #{postingWatermark}, #{receiptWatermark}, #{complete}, 'CLOSED', 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE closed_at=VALUES(closed_at), source_watermark=VALUES(source_watermark), "
            + "posting_watermark=VALUES(posting_watermark), receipt_watermark=VALUES(receipt_watermark), "
            + "watermarks_complete=VALUES(watermarks_complete), updated_at=#{now}, version=version+1")
    int upsertCutoff(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId,
            @Param("closedAt") Timestamp closedAt, @Param("sourceWatermark") String sourceWatermark,
            @Param("postingWatermark") String postingWatermark, @Param("receiptWatermark") String receiptWatermark,
            @Param("complete") int complete, @Param("now") Timestamp now);

    @Select("SELECT * FROM reconciliation_cutoff WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND cutoff_id=#{cutoffId} FOR UPDATE")
    Map<String, Object> lockCutoff(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("cutoffId") String cutoffId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Insert("INSERT INTO source_execution_fact (id, enterprise_id, warehouse_id, source_service, command_id, "
            + "business_effect_key, fact_kind, quantity, occurred_at, watermark_token, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{sourceService}, #{commandId}, #{effectKey}, "
            + "#{factKind}, #{quantity}, #{occurredAt}, #{watermark}, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertFactIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectKey") String effectKey,
            @Param("factKind") String factKind, @Param("quantity") BigDecimal quantity,
            @Param("occurredAt") Timestamp occurredAt, @Param("watermark") String watermark,
            @Param("now") Timestamp now);

    @Select("SELECT b.id, b.sku_id, b.on_hand_qty, b.reserved_qty, b.free_execution_claim_qty, k.serial_enabled "
            + "FROM stock_balance b JOIN sku k ON k.enterprise_id=b.enterprise_id AND k.id=b.sku_id "
            + "WHERE b.enterprise_id=#{enterpriseId} AND b.warehouse_id=#{warehouseId} AND b.updated_at<#{cutoff} "
            + "ORDER BY b.id LIMIT #{limit}")
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);

    @Select("SELECT l.balance_id, l.on_hand_after, l.reserved_after FROM stock_ledger l "
            + "INNER JOIN (SELECT balance_id, MAX(balance_version) AS v FROM stock_ledger "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND occurred_at<#{cutoff} "
            + "GROUP BY balance_id) t ON l.balance_id=t.balance_id AND l.balance_version=t.v "
            + "WHERE l.enterprise_id=#{enterpriseId} AND l.warehouse_id=#{warehouseId}")
    List<Map<String, Object>> listLedgerCutoff(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    @Select("SELECT l.balance_id, SUM(l.remaining_qty) AS remaining_qty FROM reservation_line l "
            + "JOIN reservation r ON r.enterprise_id=l.enterprise_id AND r.warehouse_id=l.warehouse_id "
            + "AND r.id=l.reservation_id WHERE l.enterprise_id=#{enterpriseId} AND l.warehouse_id=#{warehouseId} "
            + "AND r.state IN ('TRIED','CONFIRMED') GROUP BY l.balance_id")
    List<Map<String, Object>> listReservedRemaining(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId);

    @Select("SELECT balance_id, COUNT(*) AS serial_count FROM local_serial "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND state IN ('HOLD_RECEIVED','AUTHORIZED','SEALED') AND balance_id IS NOT NULL "
            + "GROUP BY balance_id")
    List<Map<String, Object>> listSerialCounts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId);

    @Select("SELECT source_service, command_id, business_effect_key, fact_kind, quantity, occurred_at "
            + "FROM source_execution_fact WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND occurred_at<#{cutoff}")
    List<Map<String, Object>> listFacts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    @Select("SELECT source_service, command_id, business_effect_key, quantity, created_at FROM stock_posting "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND created_at<#{cutoff}")
    List<Map<String, Object>> listPostings(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Insert("INSERT INTO reconciliation_case (id, enterprise_id, warehouse_id, cutoff_id, case_type, discrepancy_code, "
            + "scope_id, sku_id, expected_qty, actual_qty, state, evidence_ref, remediation_operation_id, approved_by, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{cutoffId}, "
            + "#{caseType}, #{code}, #{scopeId}, #{skuId}, #{expected}, #{actual}, 'OPEN', #{evidence}, NULL, NULL, "
            + "0, #{now}, #{now}) ON DUPLICATE KEY UPDATE updated_at=#{now}")
    int insertCaseIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId,
            @Param("caseType") String caseType, @Param("code") String code, @Param("scopeId") String scopeId,
            @Param("skuId") String skuId, @Param("expected") BigDecimal expected, @Param("actual") BigDecimal actual,
            @Param("evidence") String evidence, @Param("now") Timestamp now);

    @Select("SELECT * FROM reconciliation_case WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND cutoff_id=#{cutoffId} ORDER BY case_type, scope_id")
    List<Map<String, Object>> listCases(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId);

    @Select("SELECT * FROM reconciliation_case WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{id} FOR UPDATE")
    Map<String, Object> lockCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    @Select("SELECT * FROM reconciliation_case WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND cutoff_id=#{cutoffId} AND case_type=#{caseType} AND scope_id=#{scopeId}")
    Map<String, Object> findCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("cutoffId") String cutoffId, @Param("caseType") String caseType, @Param("scopeId") String scopeId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Update("UPDATE reconciliation_case SET state=#{toState}, approved_by=#{approvedBy}, "
            + "remediation_operation_id=#{operationId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{id} "
            + "AND version=#{expected} AND state=#{fromState}")
    int casCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("expected") long expected, @Param("approvedBy") String approvedBy,
            @Param("operationId") String operationId, @Param("now") Timestamp now);

    @Select("SELECT id, version, case_type, scope_id, state FROM reconciliation_case "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND cutoff_id=#{cutoffId} "
            + "AND state IN ('REMEDIATING','VERIFYING')")
    List<Map<String, Object>> listRepairing(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId);
}
