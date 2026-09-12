package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 流水与库存变化事件共用同一事务，防止旁路调整遗漏查询投影。 */
public final class InventoryLedgerWriter {
    private InventoryLedgerWriter() { }

    /** 调用方负责锁定余额并提交事务；任何写入失败都必须回滚整个调整。 */
    public static void record(SqlSession session, InventoryMapper mapper, String enterpriseId, String warehouseId,
            String operationId, int entryNo, String balanceId, BigDecimal onHandDelta, BigDecimal reservedDelta,
            BigDecimal onHandAfter, BigDecimal reservedAfter, BigDecimal claimAfter, long balanceVersion,
            String reason, String documentId, String actorId, Timestamp now) {
        String ledgerId = UUID.randomUUID().toString();
        if (mapper.insertLedger(ledgerId, enterpriseId, warehouseId, operationId, entryNo, balanceId, onHandDelta, reservedDelta,
                BigDecimal.ZERO, onHandAfter, reservedAfter, claimAfter, balanceVersion, reason, documentId, actorId, now,
                now) != 1) throw new InventoryException("VERSION_CONFLICT", "库存流水未写入");
        String payload = com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(Map.of(
                "schemaVersion", com.lrj.wms.inventory.compat.CompatibilityGate.CURRENT_EVENT_SCHEMA, "onHandDelta", onHandDelta.toPlainString(),
                "reservedDelta", reservedDelta.toPlainString(), "onHandAfter", onHandAfter.toPlainString(),
                "reservedAfter", reservedAfter.toPlainString(), "ledgerEntryId", ledgerId,
                "requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId()));
        if (session.getMapper(OutboxMapper.class).insertPending(UUID.randomUUID().toString(), enterpriseId, warehouseId,
                InventoryCodes.AGGREGATE_STOCK_BALANCE, balanceId, balanceVersion, InventoryCodes.EVENT_BALANCE_CHANGED,
                operationId, payload, now) != 1) throw new InventoryException("VERSION_CONFLICT", "库存变化事件未写入");
    }
}
