package com.lrj.wms.inventory.query.application;

import com.lrj.wms.inventory.inventory.domain.InventoryException;
import com.lrj.wms.inventory.query.persistence.InventoryHttpQueryMapper;
import com.lrj.wms.inventory.query.web.InventoryHttpJson;

import org.apache.ibatis.session.SqlSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 流水与效果只读查询；库是权威，不在这里改余额。 */
public final class InventoryAuditService {
    private final SqlSession session;

    /** 显式接收 InventoryAuditService 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public InventoryAuditService(SqlSession session) {
        this.session = session;
    }

    /** 按仓和游标读取账本，避免全量扫描及跨仓数据混合。 */
    public Map<String, Object> listLedger(
            String enterpriseId, String warehouseId, String balanceId, String cursor, int limit) {
        InventoryHttpQueryMapper mapper = mapper();
        if (mapper.getBalance(enterpriseId, warehouseId, balanceId) == null) {
            throw new InventoryException("BALANCE_NOT_FOUND", "库存桶不存在");
        }
        int size = pageSize(limit);
        return page(
                mapper.listLedger(enterpriseId, warehouseId, balanceId, blankToNull(cursor), size),
                size);
    }

    /** 在允许的仓集合内汇总操作事实，不能跨越调用方的观察范围。 */
    public Map<String, Object> getOperation(
            String enterpriseId, String operationId, List<String> warehouseIds) {
        List<Map<String, Object>> entries =
                mapper().listLedgerByOperation(enterpriseId, operationId, warehouseIds);
        if (entries.isEmpty()) {
            throw new InventoryException("OPERATION_NOT_FOUND", "操作不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("entries", entries);
        return body;
    }

    /** 按仓、任务与游标读取效果，避免把其他任务效果混入结果。 */
    public Map<String, Object> listEffects(
            String enterpriseId, String warehouseId, String taskId, String cursor, int limit) {
        int size = pageSize(limit);
        return page(
                mapper().listEffects(
                                enterpriseId,
                                warehouseId,
                                blankToNull(taskId),
                                blankToNull(cursor),
                                size),
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
