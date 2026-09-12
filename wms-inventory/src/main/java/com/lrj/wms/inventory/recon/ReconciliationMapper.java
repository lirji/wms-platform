package com.lrj.wms.inventory.recon;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/** 内部对账只读权威库存并落差异单。禁止改写 stock_balance。 */
public interface ReconciliationMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    /** upsertCutoff：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int upsertCutoff(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId,
            @Param("closedAt") Timestamp closedAt, @Param("sourceWatermark") String sourceWatermark,
            @Param("postingWatermark") String postingWatermark, @Param("receiptWatermark") String receiptWatermark,
            @Param("complete") int complete, @Param("now") Timestamp now);

    /** lockCutoff：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockCutoff(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("cutoffId") String cutoffId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    /** insertFactIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertFactIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectKey") String effectKey,
            @Param("factKind") String factKind, @Param("quantity") BigDecimal quantity,
            @Param("occurredAt") Timestamp occurredAt, @Param("watermark") String watermark,
            @Param("now") Timestamp now);

    /** listBalances：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listBalances(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);

    /** listLedgerCutoff：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listLedgerCutoff(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    /** listReservedRemaining：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listReservedRemaining(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId);

    /** listSerialCounts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listSerialCounts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId);

    /** listFacts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listFacts(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    /** listPostings：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listPostings(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoff") Timestamp cutoff);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    /** insertCaseIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertCaseIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId,
            @Param("caseType") String caseType, @Param("code") String code, @Param("scopeId") String scopeId,
            @Param("skuId") String skuId, @Param("expected") BigDecimal expected, @Param("actual") BigDecimal actual,
            @Param("evidence") String evidence, @Param("now") Timestamp now);

    /** listCases：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listCases(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId);

    /** 对外差异单查询采用有界主键游标。 */
    List<Map<String, Object>> listCasesPage(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** lockCase：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** findCase：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> findCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("cutoffId") String cutoffId, @Param("caseType") String caseType, @Param("scopeId") String scopeId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    /** casCase：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casCase(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("expected") long expected, @Param("approvedBy") String approvedBy,
            @Param("operationId") String operationId, @Param("now") Timestamp now);

    /** listRepairing：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listRepairing(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cutoffId") String cutoffId);
}
