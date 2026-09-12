package com.lrj.wms.outbound.order;

import com.lrj.wms.outbound.protocol.SourceProtocolService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 出库单、拣货任务、包裹与发运前取消。实物累计在本库完成后写来源命令，不持库存事务。
 */
public final class OutboundOrderService {
    public static final String STATUS_PENDING_AUTHORIZATION = "PENDING_AUTHORIZATION";
    public static final String STATUS_ALLOCATED = "ALLOCATED";
    public static final String STATUS_PICKING = "PICKING";
    public static final String STATUS_PACKING = "PACKING";
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String TASK_PICK = "PICK";
    public static final String TASK_RESTOCK = "RESTOCK";
    public static final String TASK_PLANNED = "PLANNED";
    public static final String TASK_STARTED = "STARTED";
    public static final String TASK_COMPLETED = "COMPLETED";
    public static final String PACKAGE_OPEN = "OPEN";

    private final SqlSession session;
    private final Clock clock;

    public OutboundOrderService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> getOrder(String enterpriseId, String warehouseId, String orderId) {
        Map<String, Object> order = mapper().getOrder(enterpriseId, warehouseId, orderId);
        if (order == null) {
            throw new OutboundException("UNKNOWN_ORDER", "出库单不存在");
        }
        Map<String, Object> body = orderView(order);
        body.put("lines", mapper().listLines(enterpriseId, warehouseId, orderId));
        body.put("tasks", mapper().listTasks(enterpriseId, warehouseId, orderId));
        return body;
    }

    public List<Map<String, Object>> listOrders(String enterpriseId, String warehouseId, int limit) {
        return mapper().listOrders(enterpriseId, warehouseId, limit);
    }

    /** 按分配尝试幂等建单。重放返回原单，不写出库库存表。 */
    public Map<String, Object> createFromAllocation(String enterpriseId, String warehouseId, String allocationId,
            String attemptId, String ownerId, String authorizationId, List<Map<String, Object>> lines) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(warehouseId, "INVALID_WAREHOUSE", "仓库不能为空");
        requireId(allocationId, "INVALID_ALLOCATION", "allocation不能为空");
        requireId(attemptId, "INVALID_ATTEMPT", "attempt不能为空");
        requireId(ownerId, "INVALID_OWNER", "货主不能为空");
        // 建单请求中的授权标识不能证明 TCC 已提交；只能由核验入口绑定权威授权记录。
        if (lines == null || lines.isEmpty()) {
            throw new OutboundException("INVALID_LINE", "出库行不能为空");
        }
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        String orderId = UUID.randomUUID().toString();
        mapper.insertOrderIgnore(orderId, enterpriseId, warehouseId, allocationId, attemptId, ownerId,
                null, STATUS_PENDING_AUTHORIZATION, now);
        Map<String, Object> order = mapper.lockOrderByAttempt(enterpriseId, warehouseId, allocationId, attemptId);
        if (order == null) {
            throw new OutboundException("VERSION_CONFLICT", "出库单创建竞争");
        }
        if (orderId.equals(String.valueOf(order.get("id")))) {
            for (Map<String, Object> line : lines) {
                mapper.insertLineIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, orderId,
                        required(line, "orderLineId"), required(line, "skuId"), requiredQty(line.get("qty")),
                        line.get("baseUnit") == null ? "EA" : String.valueOf(line.get("baseUnit")), now);
            }
        }
        return orderView(order);
    }

    /** 规划拣货任务。授权为空拒绝进入 PICKING。 */
    public Map<String, Object> planPickTask(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String sourceLocationId, String stagingLocationId, BigDecimal plannedQty) {
        return planPickTask(enterpriseId, warehouseId, orderId, orderLineId, sourceLocationId, stagingLocationId, plannedQty, UUID.randomUUID().toString());
    }

    /** 外部规划必须提供稳定命令键；未完成任务数量也占用可规划额度。 */
    public Map<String, Object> planPickTask(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String sourceLocationId, String stagingLocationId, BigDecimal plannedQty, String commandId) {
        requireId(commandId, "INVALID_ARGUMENT", "规划命令键不能为空");
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        Map<String, Object> order = requireOrder(mapper, enterpriseId, warehouseId, orderId);
        requireAuthorization(enterpriseId, warehouseId, order);
        Map<String, Object> line = requireLineByOrder(mapper, enterpriseId, warehouseId, orderId, orderLineId);
        Map<String, Object> existing = mapper.plannedTaskByKey(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            if (!orderId.equals(existing.get("document_id")) || !line.get("id").equals(existing.get("document_line_id"))
                    || !java.util.Objects.equals(sourceLocationId, existing.get("source_location_id"))
                    || !java.util.Objects.equals(stagingLocationId, existing.get("target_location_id"))
                    || plannedQty == null || plannedQty.compareTo(decimal(existing.get("planned_qty"))) != 0) {
                throw new com.lrj.wms.runtime.command.CommandConflictException();
            }
            return Map.of("taskId", existing.get("id"), "lineId", line.get("id"), "plannedQty", existing.get("planned_qty"), "state", existing.get("state"));
        }
        BigDecimal remain = remainUnpicked(line).subtract(mapper.pendingPickQty(enterpriseId, warehouseId, String.valueOf(line.get("id"))));
        if (plannedQty == null || plannedQty.signum() <= 0 || plannedQty.compareTo(remain) > 0) {
            throw new OutboundException("OVER_PICK", "计划拣货超过剩余分配量");
        }
        String taskId = UUID.randomUUID().toString();
        mapper.insertTask(taskId, enterpriseId, warehouseId, TASK_PICK, orderId, String.valueOf(line.get("id")),
                sourceLocationId, stagingLocationId, plannedQty, TASK_PLANNED, now);
        if (mapper.bindPlanningKey(enterpriseId, warehouseId, taskId, commandId) != 1) {
            throw new OutboundException("VERSION_CONFLICT", "规划幂等键绑定冲突");
        }
        mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, STATUS_PICKING, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("lineId", line.get("id"));
        body.put("plannedQty", plannedQty);
        body.put("state", TASK_PLANNED);
        return body;
    }

    /** 部分拣货实物：累计 physical 并提交 PICK 来源命令。不写库存余额。 */
    public Map<String, Object> pickPartial(String enterpriseId, String warehouseId, String taskId, String commandId,
            String actorId, BigDecimal qty) {
        return pickPartial(enterpriseId, warehouseId, taskId, commandId, actorId, qty, commandId);
    }

    /** 新分批有独立事实身份；同分批换命令键仍复用原结果。 */
    public Map<String, Object> pickPartial(String enterpriseId, String warehouseId, String taskId, String commandId,
            String actorId, BigDecimal qty, String pickPartId) {
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        String parentOrder = mapper.taskOrderId(enterpriseId, warehouseId, taskId);
        if (parentOrder == null) throw new OutboundException("UNKNOWN_TASK", "拣货任务不存在");
        Map<String, Object> order = requireOrder(mapper, enterpriseId, warehouseId, parentOrder);
        Map<String, Object> task = mapper.lockTask(enterpriseId, warehouseId, taskId);
        if (task == null) {
            throw new OutboundException("UNKNOWN_TASK", "拣货任务不存在");
        }
        requireAuthorization(enterpriseId, warehouseId, order);
        Map<String, Object> line = requireLine(mapper, enterpriseId, warehouseId, String.valueOf(task.get("document_line_id")));
        if (qty == null || qty.signum() <= 0) {
            throw new OutboundException("INVALID_QTY", "拣货数量必须为正");
        }
        var protocol = new SourceProtocolService(session, clock);
        String partId = com.lrj.wms.runtime.command.CommandReplay.partId(taskId,
                pickPartId == null || pickPartId.isBlank() ? commandId : pickPartId);
        Map<String, Object> replay = protocol.replayIfPresent(SourceProtocolService.ACTION_PICK, "SUB_ACTION",
                enterpriseId, warehouseId, commandId, String.valueOf(order.get("id")), partId, String.valueOf(line.get("id")), qty);
        if (replay != null) {
            replay.put("taskId", taskId); replay.put("lineId", line.get("id")); return replay;
        }
        BigDecimal remainTask = decimal(task.get("planned_qty")).subtract(decimal(task.get("completed_qty")));
        if (qty.compareTo(remainTask) > 0 || qty.compareTo(remainUnpicked(line)) > 0) {
            throw new OutboundException("OVER_PICK", "拣货超过任务或行剩余量");
        }
        Map<String, Object> command = protocol.submitPick(enterpriseId, warehouseId, commandId,
                String.valueOf(order.get("id")), partId, String.valueOf(line.get("id")), actorId, qty);
        if (!Boolean.TRUE.equals(command.get("replayed"))) {
            if (mapper.addPickedPhysical(enterpriseId, warehouseId, String.valueOf(line.get("id")), qty, now) != 1) {
                throw new OutboundException("OVER_PICK", "拣货行更新冲突");
            }
            String taskState = qty.compareTo(remainTask) == 0 ? TASK_COMPLETED : TASK_STARTED;
            if (mapper.addTaskCompleted(enterpriseId, warehouseId, taskId, qty, taskState, now) != 1) {
                throw new OutboundException("VERSION_CONFLICT", "拣货任务更新冲突");
            }
        }
        command.put("taskId", taskId);
        command.put("lineId", line.get("id"));
        return command;
    }

    /** T3：仅新 inbox 增加 picked_posted。 */
    public Map<String, Object> consumePick(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            mapper().addPickedPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED", now());
        }
        result.put("line", mapper().lockLine(enterpriseId, warehouseId, lineId));
        return result;
    }

    /** 包装已拣未装数量。不派发设备。 */
    public Map<String, Object> pack(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String packageNo, BigDecimal qty) {
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        Map<String, Object> order = requireOrder(mapper, enterpriseId, warehouseId, orderId);
        requireAuthorization(enterpriseId, warehouseId, order);
        Map<String, Object> line = requireLineByOrder(mapper, enterpriseId, warehouseId, orderId, orderLineId);
        BigDecimal unpacked = decimal(line.get("picked_physical_qty")).subtract(decimal(line.get("packed_physical_qty")));
        if (qty == null || qty.signum() <= 0 || qty.compareTo(unpacked) > 0) {
            throw new OutboundException("OVER_PACK", "包装超过已拣未装量");
        }
        String packageId = UUID.randomUUID().toString();
        mapper.insertPackage(packageId, enterpriseId, warehouseId, orderId, packageNo, PACKAGE_OPEN, now);
        mapper.insertPackageLine(UUID.randomUUID().toString(), enterpriseId, warehouseId, packageId,
                String.valueOf(line.get("id")), qty, now);
        mapper.addPackedPhysical(enterpriseId, warehouseId, String.valueOf(line.get("id")), qty, now);
        mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, STATUS_PACKING, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        body.put("packageNo", packageNo);
        body.put("lineId", line.get("id"));
        body.put("qty", qty);
        return body;
    }

    /** 发运已包装未发量。超过包装未发拒绝。不写库存余额。 */
    public Map<String, Object> shipPartial(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String commandId, String actorId, BigDecimal qty) {
        return shipPartial(enterpriseId, warehouseId, orderId, orderLineId, commandId, actorId, qty, commandId);
    }

    /** 每个发运分批独立过账，同分批与同命令重试不再累计数量。 */
    public Map<String, Object> shipPartial(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String commandId, String actorId, BigDecimal qty, String shipmentPartId) {
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        Map<String, Object> order = requireOrder(mapper, enterpriseId, warehouseId, orderId);
        requireAuthorization(enterpriseId, warehouseId, order);
        Map<String, Object> line = requireLineByOrder(mapper, enterpriseId, warehouseId, orderId, orderLineId);
        if (qty == null || qty.signum() <= 0) {
            throw new OutboundException("INVALID_QTY", "发运数量必须为正");
        }
        var protocol = new SourceProtocolService(session, clock);
        String partId = shipmentPartId == null || shipmentPartId.isBlank() ? commandId : shipmentPartId;
        Map<String, Object> replay = protocol.replayIfPresent(SourceProtocolService.ACTION_SHIP, "SHIPMENT_PART",
                enterpriseId, warehouseId, commandId, orderId, partId, String.valueOf(line.get("id")), qty);
        if (replay != null) {
            replay.put("lineId", line.get("id")); replay.put("shippedQty", qty); return replay;
        }
        BigDecimal unshipped = decimal(line.get("packed_physical_qty")).subtract(decimal(line.get("shipped_physical_qty")));
        if (qty.compareTo(unshipped) > 0) {
            throw new OutboundException("OVER_SHIP", "发运超过已包装未发量");
        }
        Map<String, Object> command = protocol.submitShip(enterpriseId, warehouseId,
                commandId, orderId, partId, String.valueOf(line.get("id")), actorId, qty);
        if (!Boolean.TRUE.equals(command.get("replayed"))
                && mapper.addShippedPhysical(enterpriseId, warehouseId, String.valueOf(line.get("id")), qty, now) != 1) {
            throw new OutboundException("OVER_SHIP", "发运超过已包装未发量");
        }
        command.put("lineId", line.get("id"));
        command.put("shippedQty", qty);
        settleOrder(mapper, enterpriseId, warehouseId, orderId, String.valueOf(line.get("id")), now);
        return command;
    }

    /** T3：仅新 inbox 增加 shipped_posted。 */
    public Map<String, Object> consumeShip(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            if (mapper().addShippedPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED", now()) != 1) {
                throw new OutboundException("OVER_SHIP", "过账发运超过实物发运");
            }
        }
        result.put("line", mapper().lockLine(enterpriseId, warehouseId, lineId));
        return result;
    }

    /** 发运前取消未拣剩余量，写回库任务与来源取消命令。已拣未发不直接回滚库存。 */
    public Map<String, Object> cancelUnpicked(String enterpriseId, String warehouseId, String orderId, String orderLineId,
            String commandId, String actorId) {
        Timestamp now = now();
        OutboundOrderMapper mapper = mapper();
        Map<String, Object> order = requireOrder(mapper, enterpriseId, warehouseId, orderId);
        requireAuthorization(enterpriseId, warehouseId, order);
        Map<String, Object> line = requireLineByOrder(mapper, enterpriseId, warehouseId, orderId, orderLineId);
        var protocol = new SourceProtocolService(session, clock);
        String taskId = com.lrj.wms.runtime.command.CommandReplay.partId(orderId, commandId);
        Map<String, Object> replay = protocol.replayIfPresent(SourceProtocolService.ACTION_CANCEL, "SUB_ACTION",
                enterpriseId, warehouseId, commandId, orderId, taskId, String.valueOf(line.get("id")), null);
        if (replay != null) {
            replay.put("cancelledQty", replay.get("qty")); replay.put("taskId", taskId);
            replay.put("action", SourceProtocolService.ACTION_CANCEL); return replay;
        }
        BigDecimal remain = remainUnpicked(line);
        if (remain.signum() <= 0) {
            throw new OutboundException("NOTHING_TO_CANCEL", "没有可取消的未拣量");
        }
        Map<String, Object> command = protocol.submitCancel(enterpriseId, warehouseId,
                commandId, orderId, taskId, String.valueOf(line.get("id")), actorId, remain);
        if (!Boolean.TRUE.equals(command.get("replayed"))) {
            if (mapper.addCancelled(enterpriseId, warehouseId, String.valueOf(line.get("id")), remain, now) != 1) {
                throw new OutboundException("VERSION_CONFLICT", "取消数量更新冲突");
            }
            mapper.cancelOpenPickTasks(enterpriseId, warehouseId, String.valueOf(line.get("id")), now);
            mapper.insertTask(taskId, enterpriseId, warehouseId, TASK_RESTOCK, orderId, String.valueOf(line.get("id")),
                    null, null, remain, TASK_PLANNED, now);
            settleOrder(mapper, enterpriseId, warehouseId, orderId, String.valueOf(line.get("id")), now);
        }
        command.put("cancelledQty", remain);
        command.put("taskId", taskId);
        command.put("action", SourceProtocolService.ACTION_CANCEL);
        return command;
    }

    private Map<String, Object> requireOrder(OutboundOrderMapper mapper, String enterpriseId, String warehouseId,
            String orderId) {
        Map<String, Object> order = mapper.lockOrder(enterpriseId, warehouseId, orderId);
        if (order == null) {
            throw new OutboundException("UNKNOWN_ORDER", "出库单不存在");
        }
        return order;
    }

    private Map<String, Object> requireLine(OutboundOrderMapper mapper, String enterpriseId, String warehouseId,
            String lineId) {
        Map<String, Object> line = mapper.lockLine(enterpriseId, warehouseId, lineId);
        if (line == null) {
            throw new OutboundException("UNKNOWN_LINE", "出库行不存在");
        }
        return line;
    }

    private Map<String, Object> requireLineByOrder(OutboundOrderMapper mapper, String enterpriseId, String warehouseId,
            String orderId, String orderLineId) {
        Map<String, Object> line = mapper.lockLineByOrderLine(enterpriseId, warehouseId, orderId, orderLineId);
        if (line == null) {
            throw new OutboundException("UNKNOWN_LINE", "出库行不存在");
        }
        return line;
    }

    private void requireAuthorization(String enterpriseId, String warehouseId, Map<String, Object> order) {
        new OutboundAuthorizationService(session, clock).requireExecutable(enterpriseId, warehouseId, order);
    }

    /** 头状态只能由所有行汇总；单行完成不能阻止其他行继续执行。调用方已经锁住订单。 */
    private void settleOrder(OutboundOrderMapper mapper, String enterpriseId, String warehouseId, String orderId,
            String lineId, Timestamp now) {
        var lines = mapper.listLines(enterpriseId, warehouseId, orderId);
        boolean shipped = false;
        if (lines.isEmpty()) return;
        for (var line : lines) {
            BigDecimal shippedQty = decimal(line.get("shipped_physical_qty"));
            BigDecimal settled = shippedQty.add(decimal(line.get("cancelled_qty")));
            if (settled.compareTo(decimal(line.get("allocated_qty"))) != 0) return;
            shipped |= shippedQty.signum() > 0;
        }
        if (mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, shipped ? STATUS_SHIPPED : STATUS_CANCELLED, now) != 1) {
            throw new OutboundException("VERSION_CONFLICT", "出库头状态汇总冲突");
        }
    }

    private static BigDecimal remainUnpicked(Map<String, Object> line) {
        return decimal(line.get("allocated_qty")).subtract(decimal(line.get("picked_physical_qty")))
                .subtract(decimal(line.get("cancelled_qty")));
    }

    private OutboundOrderMapper mapper() {
        return session.getMapper(OutboundOrderMapper.class);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Map<String, Object> orderView(Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.get("id"));
        body.put("status", order.get("status"));
        body.put("allocationId", order.get("allocation_id"));
        body.put("attemptId", order.get("attempt_id"));
        body.put("executionAuthorizationId", order.get("execution_authorization_id"));
        return body;
    }

    private static void requireId(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new OutboundException(code, message);
        }
    }

    private static String required(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new OutboundException("INVALID_LINE", "缺少" + key);
        }
        return String.valueOf(value);
    }

    private static BigDecimal requiredQty(Object value) {
        if (value == null) {
            throw new OutboundException("INVALID_LINE", "数量不能为空");
        }
        BigDecimal qty = value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
        if (qty.signum() <= 0) {
            throw new OutboundException("INVALID_LINE", "数量必须为正");
        }
        return qty;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
