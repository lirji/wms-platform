package com.lrj.wms.inbound.receipt;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;

/** 入库仓任务列表、详情与领取。仅 PUTAWAY 属于本域。 */
public final class InboundTaskService {
    static final String TYPE_PUTAWAY = "PUTAWAY";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");

    private final SqlSession session;
    private final Clock clock;

    public InboundTaskService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> list(String enterpriseId, String warehouseId, String taskType, String cursor, int limit) {
        requireType(taskType);
        int size = pageSize(limit);
        List<Map<String, Object>> items = mapper().listTasks(enterpriseId, warehouseId, taskType, blankToNull(cursor),
                size);
        return page(items, size);
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String taskId) {
        Map<String, Object> task = mapper().getTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new InboundException("RESOURCE_NOT_FOUND", "任务不存在");
        }
        return task;
    }

    public Map<String, Object> claim(String enterpriseId, String warehouseId, String taskId, String workerId,
            long expectedVersion) {
        if (workerId == null || workerId.isBlank()) {
            throw new InboundException("INVALID_WORKER", "worker不能为空");
        }
        InboundTaskMapper mapper = mapper();
        Map<String, Object> task = mapper.lockTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new InboundException("RESOURCE_NOT_FOUND", "任务不存在");
        }
        if (TERMINAL.contains(String.valueOf(task.get("state")))) {
            throw new InboundException("TASK_NOT_CLAIMABLE", "终态任务不能领取");
        }
        if (asLong(task.get("version")) != expectedVersion) {
            throw new InboundException("VERSION_CONFLICT", "任务版本冲突");
        }
        long epoch = asLong(task.get("claim_epoch")) + 1;
        if (mapper.claimTask(enterpriseId, warehouseId, taskId, workerId, epoch, expectedVersion,
                Timestamp.from(clock.instant())) != 1) {
            throw new InboundException("VERSION_CONFLICT", "任务版本冲突");
        }
        Map<String, Object> body = new LinkedHashMap<>(get(enterpriseId, warehouseId, taskId));
        body.put("taskId", taskId);
        body.put("workerId", workerId);
        body.put("claimEpoch", epoch);
        return body;
    }

    static void requireType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new InboundException("TASK_TYPE_REQUIRED", "必须携带taskType");
        }
        if (!TYPE_PUTAWAY.equals(taskType)) {
            throw new InboundException("TASK_TYPE_UNSUPPORTED", "入库只受理PUTAWAY任务");
        }
    }

    private InboundTaskMapper mapper() {
        return session.getMapper(InboundTaskMapper.class);
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

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
