package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.inventory.InventoryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 流水与效果只读查询；库是权威，不在这里改余额。 */
public final class InventoryAuditService {
    private final SqlSession session;

    public InventoryAuditService(SqlSession session) {
        this.session = session;
    }

    public Map<String, Object> listLedger(String enterpriseId, String warehouseId, String balanceId, String cursor,
            int limit) {
        InventoryHttpQueryMapper mapper = mapper();
        if (mapper.getBalance(enterpriseId, warehouseId, balanceId) == null) {
            throw new InventoryException("BALANCE_NOT_FOUND", "库存桶不存在");
        }
        int size = pageSize(limit);
        return page(mapper.listLedger(enterpriseId, warehouseId, balanceId, blankToNull(cursor), size), size);
    }

    public Map<String, Object> getOperation(String enterpriseId, String operationId, List<String> warehouseIds) {
        List<Map<String, Object>> entries = mapper().listLedgerByOperation(enterpriseId, operationId, warehouseIds);
        if (entries.isEmpty()) {
            throw new InventoryException("OPERATION_NOT_FOUND", "操作不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("entries", entries);
        return body;
    }

    public Map<String, Object> listEffects(String enterpriseId, String warehouseId, String taskId, String cursor,
            int limit) {
        int size = pageSize(limit);
        return page(mapper().listEffects(enterpriseId, warehouseId, blankToNull(taskId), blankToNull(cursor), size),
                size);
    }

    private InventoryHttpQueryMapper mapper() {
        return session.getMapper(InventoryHttpQueryMapper.class);
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, int limit) {
        List<Map<String, Object>> rows = InventoryHttpJson.rows(items);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("limit", limit);
        if (items.size() == limit && !items.isEmpty()) {
            body.put("nextCursor", String.valueOf(items.getLast().get("id")));
        }
        return body;
    }

    private static int pageSize(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 100);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
