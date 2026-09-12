package com.lrj.wms.outbound.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 出库仓任务列表、详情与领取。仅 PICK/RESTOCK 属于本域。 */
public final class OutboundTaskService {
    private static final Set<String> TYPES = Set.of(OutboundOrderService.TASK_PICK, OutboundOrderService.TASK_RESTOCK);
    private static final Set<String> TERMINAL = Set.of(OutboundOrderService.TASK_COMPLETED, "CANCELLED");

    private final SqlSession session;
    private final Clock clock;

    public OutboundTaskService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> list(String enterpriseId, String warehouseId, String taskType, String cursor, int limit) {
        requireType(taskType);
        int size = pageSize(limit);
        List<Map<String, Object>> items = queries().listTasks(enterpriseId, warehouseId, taskType, blankToNull(cursor),
                size);
        return page(items, size);
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String taskId) {
        Map<String, Object> task = queries().getTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new OutboundException("UNKNOWN_TASK", "拣货任务不存在");
        }
        return task;
    }

    public Map<String, Object> claim(String enterpriseId, String warehouseId, String taskId, String workerId,
            long expectedVersion) {
        if (workerId == null || workerId.isBlank()) {
            throw new OutboundException("INVALID_WORKER", "worker不能为空");
        }
        OutboundOrderMapper writes = session.getMapper(OutboundOrderMapper.class);
        Map<String, Object> task = writes.lockTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new OutboundException("UNKNOWN_TASK", "拣货任务不存在");
        }
        if (TERMINAL.contains(String.valueOf(task.get("state")))) {
            throw new OutboundException("TASK_NOT_CLAIMABLE", "终态任务不能领取");
        }
        if (asLong(task.get("version")) != expectedVersion) {
            throw new OutboundException("VERSION_CONFLICT", "任务版本冲突");
        }
        String actionId = firstNonBlank(task.get("action_id"), UUID.randomUUID().toString());
        String deviceCommandId = firstNonBlank(task.get("device_command_id"), actionId);
        long epoch = asLong(task.get("claim_epoch")) + 1;
        writes.claimTask(enterpriseId, warehouseId, taskId, workerId, epoch, actionId, deviceCommandId,
                Timestamp.from(clock.instant()));
        Map<String, Object> body = new LinkedHashMap<>(get(enterpriseId, warehouseId, taskId));
        body.put("taskId", taskId);
        body.put("workerId", workerId);
        body.put("claimEpoch", epoch);
        body.put("actionId", actionId);
        body.put("deviceCommandId", deviceCommandId);
        return body;
    }

    static void requireType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new OutboundException("TASK_TYPE_REQUIRED", "必须携带taskType");
        }
        if (!TYPES.contains(taskType)) {
            throw new OutboundException("TASK_TYPE_UNSUPPORTED", "出库只受理PICK或RESTOCK任务");
        }
    }

    private OutboundTaskMapper queries() {
        return session.getMapper(OutboundTaskMapper.class);
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
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

    private static String firstNonBlank(Object current, String generated) {
        return current == null || String.valueOf(current).isBlank() ? generated : String.valueOf(current);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
