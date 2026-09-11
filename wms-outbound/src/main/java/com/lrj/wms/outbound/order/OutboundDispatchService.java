package com.lrj.wms.outbound.order;

import com.lrj.wms.integration.wcs.SimulatorWcsAdapter;
import com.lrj.wms.integration.wcs.WcsCommand;
import com.lrj.wms.integration.wcs.WcsCommandPort;
import com.lrj.wms.integration.wcs.WcsDispatchResult;
import com.lrj.wms.integration.wcs.WcsReceipt;
import com.lrj.wms.integration.wcs.WcsReceiptPort;
import com.lrj.wms.integration.wcs.WcsReceiptResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 共享动作身份、STARTED 后派发、旧 worker 回执恢复。不写库存余额。
 */
public final class OutboundDispatchService {
    private final SqlSession session;
    private final Clock clock;
    private final ExecutionAuthorizationPort authorizations;
    private final WcsCommandPort commands;
    private final WcsReceiptPort receipts;

    public OutboundDispatchService(SqlSession session, Clock clock, ExecutionAuthorizationPort authorizations,
            WcsCommandPort commands, WcsReceiptPort receipts) {
        this.session = session;
        this.clock = clock;
        this.authorizations = authorizations;
        this.commands = commands;
        this.receipts = receipts;
    }

    /** 领取或换主。已有 action/device 命令沿用，不得换号。 */
    public Map<String, Object> claim(String enterpriseId, String warehouseId, String taskId, String workerId) {
        require(workerId, "INVALID_WORKER", "worker不能为空");
        Timestamp now = Timestamp.from(clock.instant());
        OutboundOrderMapper mapper = session.getMapper(OutboundOrderMapper.class);
        Map<String, Object> task = requireTask(mapper, enterpriseId, warehouseId, taskId);
        String actionId = firstNonBlank(task.get("action_id"), UUID.randomUUID().toString());
        String deviceCommandId = firstNonBlank(task.get("device_command_id"), actionId);
        long epoch = toLong(task.get("claim_epoch")) + 1;
        mapper.claimTask(enterpriseId, warehouseId, taskId, workerId, epoch, actionId, deviceCommandId, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("workerId", workerId);
        body.put("claimEpoch", epoch);
        body.put("actionId", actionId);
        body.put("deviceCommandId", deviceCommandId);
        return body;
    }

    /** STARTED 授权后派发固定 deviceCommandId。旧 epoch 不能新派工。 */
    public Map<String, Object> dispatch(String enterpriseId, String warehouseId, String taskId, String workerId,
            long claimEpoch) {
        OutboundOrderMapper mapper = session.getMapper(OutboundOrderMapper.class);
        Map<String, Object> task = requireTask(mapper, enterpriseId, warehouseId, taskId);
        guardWorker(task, workerId, claimEpoch);
        if (blank(task.get("device_command_id"))) {
            throw new OutboundException("ACTION_IDENTITY_MISSING", "派发前必须固定动作身份");
        }
        BigDecimal qty = remain(task);
        Map<String, Object> permit = authorizations.startPermit(enterpriseId, warehouseId,
                String.valueOf(task.get("device_command_id")), taskId, claimEpoch,
                String.valueOf(task.get("document_id")), taskId, String.valueOf(task.get("document_line_id")), qty);
        String permitState = String.valueOf(permit.get("permitState"));
        if ("UNKNOWN".equals(permitState)) {
            throw new OutboundException("DEVICE_UNKNOWN", "未知结果保持占用，不能新派工");
        }
        if (!"STARTED".equals(permitState)) {
            throw new OutboundException("NOT_STARTED", "未取得STARTED不得派发");
        }
        mapper.updateOrderStatus(enterpriseId, warehouseId, String.valueOf(task.get("document_id")),
                OutboundOrderService.STATUS_PICKING, Timestamp.from(clock.instant()));
        mapper.addTaskCompleted(enterpriseId, warehouseId, taskId, BigDecimal.ZERO, OutboundOrderService.TASK_STARTED,
                Timestamp.from(clock.instant()));
        WcsDispatchResult dispatched = commands.dispatch(new WcsCommand(enterpriseId, warehouseId,
                String.valueOf(task.get("device_command_id")), "PICK", taskId, qty,
                String.valueOf(task.get("device_command_id"))));
        Map<String, Object> body = new LinkedHashMap<>(permit);
        body.put("deviceCommandId", dispatched.deviceCommandId());
        body.put("dispatchState", dispatched.state());
        body.put("dispatchReplayed", dispatched.replayed());
        body.put("implementation", implementationLabel());
        return body;
    }

    /** 可信旧 worker 回执按原命令恢复，不授予新派工权。 */
    public WcsReceiptResult recoverReceipt(String enterpriseId, String warehouseId, String taskId, String eventId,
            String resultState, BigDecimal actualQty) {
        Map<String, Object> task = requireTask(session.getMapper(OutboundOrderMapper.class), enterpriseId, warehouseId,
                taskId);
        if (blank(task.get("device_command_id"))) {
            throw new OutboundException("ACTION_IDENTITY_MISSING", "没有可恢复的设备命令");
        }
        if ("UNKNOWN".equals(resultState)) {
            authorizations.markUnknown(enterpriseId, warehouseId, String.valueOf(task.get("device_command_id")));
        }
        return receipts.accept(new WcsReceipt(enterpriseId, warehouseId, String.valueOf(task.get("device_command_id")),
                eventId, resultState, actualQty, clock.instant()));
    }

    private static void guardWorker(Map<String, Object> task, String workerId, long claimEpoch) {
        if (blank(task.get("assignee_id")) || !workerId.equals(String.valueOf(task.get("assignee_id")))
                || toLong(task.get("claim_epoch")) != claimEpoch) {
            throw new OutboundException("WORKER_FENCED", "旧worker不能新派工");
        }
    }

    private Map<String, Object> requireTask(OutboundOrderMapper mapper, String enterpriseId, String warehouseId,
            String taskId) {
        Map<String, Object> task = mapper.lockTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new OutboundException("UNKNOWN_TASK", "拣货任务不存在");
        }
        return task;
    }

    private String implementationLabel() {
        if (commands instanceof SimulatorWcsAdapter simulator) {
            return simulator.implementation();
        }
        return "WCS";
    }

    private static BigDecimal remain(Map<String, Object> task) {
        BigDecimal planned = decimal(task.get("planned_qty"));
        BigDecimal completed = decimal(task.get("completed_qty"));
        BigDecimal left = planned.subtract(completed);
        return left.signum() > 0 ? left : planned;
    }

    private static void require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new OutboundException(code, message);
        }
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String firstNonBlank(Object current, String generated) {
        return blank(current) ? generated : String.valueOf(current);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
