package com.lrj.wms.inbound.receipt;

import com.lrj.wms.inbound.protocol.SourceProtocolService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 入库单收货/质检/上架。实物累计在本库完成后再写来源命令，不持库存事务。
 */
public final class InboundReceiptService {
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_RECEIVING = "RECEIVING";
    public static final String RESULT_ACCEPTED = "ACCEPTED";
    public static final String RESULT_REJECTED = "REJECTED";
    public static final String LOCATION_STORAGE = "STORAGE";
    public static final String OBSERVATION_OPEN = "OPEN";
    public static final String OBSERVATION_BOUND = "BOUND";

    private final SqlSession session;
    private final Clock clock;

    public InboundReceiptService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> getOrder(String enterpriseId, String warehouseId, String orderId) {
        Map<String, Object> order = mapper().getOrder(enterpriseId, warehouseId, orderId);
        if (order == null) {
            throw new InboundException("RESOURCE_NOT_FOUND", "入库单不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order.get("id"));
        body.put("status", order.get("status"));
        body.put("externalSource", order.get("external_source"));
        body.put("externalNo", order.get("external_no"));
        body.put("ownerId", order.get("owner_id"));
        body.put("version", order.get("version"));
        body.put("lines", mapper().listLines(enterpriseId, warehouseId, orderId));
        body.put("tasks", mapper().listTasks(enterpriseId, warehouseId, orderId));
        return body;
    }

    public List<Map<String, Object>> listOrders(String enterpriseId, String warehouseId, int limit) {
        return mapper().listOrders(enterpriseId, warehouseId, limit);
    }

    /** 创建入库单与行。同外部单号冲突由唯一键拒绝。 */
    public Map<String, Object> createOrder(String enterpriseId, String warehouseId, String orderId, String externalSource,
            String externalNo, String ownerId, List<Map<String, Object>> lines) {
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        try {
            mapper.insertOrder(orderId, enterpriseId, warehouseId, externalSource, externalNo, ownerId, STATUS_APPROVED, now);
        } catch (RuntimeException ex) {
            if (!com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(ex)) throw ex;
            throw new InboundException("DUPLICATE_DOCUMENT", "入库单已存在");
        }
        for (Map<String, Object> line : lines) {
            try {
                mapper.insertLine(String.valueOf(line.get("lineId")), enterpriseId, warehouseId, orderId,
                        String.valueOf(line.get("externalLineId")), String.valueOf(line.get("skuId")),
                        decimal(line.get("expectedQty")), String.valueOf(line.getOrDefault("unit", "EA")), now);
            } catch (RuntimeException ex) {
                if (!com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(ex)) throw ex;
                // 行主键全局唯一，复用 LINE-1 不能冒成 500。
                throw new InboundException("DUPLICATE_DOCUMENT", "入库行已存在");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", STATUS_APPROVED);
        return body;
    }

    /** 收货实物：校验剩余额度后累计 physical，并提交 RECEIVE 来源命令。 */
    public Map<String, Object> receive(String enterpriseId, String warehouseId, String orderId, String lineId,
            String commandId, String partId, String actorId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) throw new InboundException("INVALID_QTY", "收货数量必须为正");
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        Map<String, Object> line = requireLine(mapper, enterpriseId, warehouseId, lineId);
        requireLineOrder(line, orderId);
        var protocol = new SourceProtocolService(session, clock);
        Map<String, Object> replay = protocol.replayIfPresent(SourceProtocolService.ACTION_RECEIVE, "RECEIPT_PART",
                enterpriseId, warehouseId, commandId, orderId, partId, lineId, qty);
        if (replay != null) return replay;
        BigDecimal physical = decimal(line.get("received_physical_qty")).add(qty);
        if (physical.compareTo(decimal(line.get("expected_qty")).subtract(decimal(line.get("closed_qty")))) > 0) {
            throw new InboundException("OVER_RECEIVE", "收货超过剩余额度");
        }
        Map<String, Object> command = protocol.submitReceive(enterpriseId, warehouseId, commandId, orderId, partId,
                lineId, actorId, qty);
        if (!Boolean.TRUE.equals(command.get("replayed"))) {
            if (mapper.addReceivedPhysical(enterpriseId, warehouseId, lineId, qty, now) != 1) {
                throw new InboundException("VERSION_CONFLICT", "收货行更新冲突");
            }
            mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, STATUS_RECEIVING, now);
        }
        return command;
    }

    /**
     * 带设备观察的收货。同 device+session+sequence 重放恢复原命令；新分批才加实物。
     * 缺身份隔离，不自动入账。
     */
    public Map<String, Object> receiveObserved(String enterpriseId, String warehouseId, String orderId, String lineId,
            String receiptSessionId, String partId, String commandId, String deviceId, String deviceSessionId,
            long sequenceNo, String actorId, BigDecimal qty) {
        requireIdentity("收货会话", receiptSessionId);
        requireIdentity("分批身份", partId);
        requireIdentity("命令", commandId);
        requireIdentity("设备", deviceId);
        requireIdentity("设备会话", deviceSessionId);
        if (sequenceNo < 1) {
            throw new InboundException("AMBIGUOUS_OBSERVATION", "观察序号从1开始");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new InboundException("INVALID_QTY", "收货数量必须为正");
        }
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        Map<String, Object> line = requireLine(mapper, enterpriseId, warehouseId, lineId);
        requireLineOrder(line, orderId);
        String digest = observationDigest(receiptSessionId, partId, lineId, qty);
        Map<String, Object> observation = mapper.lockObservation(enterpriseId, warehouseId, deviceId, deviceSessionId,
                sequenceNo);
        if (observation != null) {
            if (!digest.equals(String.valueOf(observation.get("payload_digest")))) {
                throw new InboundException("OBSERVATION_CONFLICT", "同序号观察内容不一致");
            }
            if (OBSERVATION_BOUND.equals(String.valueOf(observation.get("state")))) {
                return observationView(observation, true, false);
            }
        }
        Map<String, Object> part = mapper.lockPart(enterpriseId, warehouseId, receiptSessionId, partId, lineId);
        var protocol = new SourceProtocolService(session, clock);
        Map<String, Object> replay = protocol.replayIfPresent(SourceProtocolService.ACTION_RECEIVE, "RECEIPT_PART",
                enterpriseId, warehouseId, commandId, receiptSessionId, partId, lineId, qty);
        boolean newPart = part == null;
        if (newPart && replay == null) {
            BigDecimal physical = decimal(line.get("received_physical_qty")).add(qty);
            if (physical.compareTo(decimal(line.get("expected_qty")).subtract(decimal(line.get("closed_qty")))) > 0) {
                throw new InboundException("OVER_RECEIVE", "收货超过剩余额度");
            }
        } else if (part != null && decimal(part.get("qty")).compareTo(qty) != 0) {
            throw new InboundException("PART_CONFLICT", "同分批数量不一致");
        }
        mapper.insertObservationIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, deviceId, deviceSessionId,
                sequenceNo, commandId, receiptSessionId, partId, lineId, commandId, digest, OBSERVATION_OPEN, now);
        observation = mapper.lockObservation(enterpriseId, warehouseId, deviceId, deviceSessionId, sequenceNo);
        if (observation == null) {
            throw new InboundException("VERSION_CONFLICT", "观察绑定竞争");
        }
        if (!digest.equals(String.valueOf(observation.get("payload_digest")))) {
            throw new InboundException("OBSERVATION_CONFLICT", "同序号观察内容不一致");
        }
        if (OBSERVATION_BOUND.equals(String.valueOf(observation.get("state")))) {
            return observationView(observation, true, false);
        }
        Map<String, Object> command = replay != null ? replay : protocol.submitReceive(enterpriseId, warehouseId,
                commandId, receiptSessionId, partId, lineId, actorId, qty);
        if (newPart) {
            mapper.insertPartIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, receiptSessionId, orderId,
                    lineId, partId, qty, String.valueOf(command.get("commandId")), actorId, now);
            part = mapper.lockPart(enterpriseId, warehouseId, receiptSessionId, partId, lineId);
            if (part == null) {
                throw new InboundException("VERSION_CONFLICT", "分批登记竞争");
            }
            if (!Boolean.TRUE.equals(command.get("replayed"))) {
                if (mapper.addReceivedPhysical(enterpriseId, warehouseId, lineId, qty, now) != 1) {
                    throw new InboundException("VERSION_CONFLICT", "收货行更新冲突");
                }
                mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, STATUS_RECEIVING, now);
            }
        }
        String boundCommand = String.valueOf(command.get("commandId"));
        String effectId = String.valueOf(command.get("effectId"));
        mapper.bindObservation(enterpriseId, warehouseId, String.valueOf(observation.get("id")), effectId, boundCommand,
                now);
        Map<String, Object> bound = mapper.lockObservation(enterpriseId, warehouseId, deviceId, deviceSessionId, sequenceNo);
        return observationView(bound, Boolean.TRUE.equals(command.get("replayed")), !newPart);
    }

    /** 来源拥有订单/行和执行事实，库位与批次由现场显式提交；库存服务最终校验主数据。 */
    public void bindReceiveContext(String enterpriseId, String warehouseId, String orderId, String lineId,
            Map<String, Object> result, String locationId, String lotId) {
        if (locationId == null || locationId.isBlank() || lotId == null || lotId.isBlank()) {
            throw new InboundException("MISSING_POSTING_CONTEXT", "收货需要明确库位与批次标识");
        }
        var line = requireLine(mapper(), enterpriseId, warehouseId, lineId);
        requireLineOrder(line, orderId);
        var order = mapper().getOrder(enterpriseId, warehouseId, orderId);
        if (order == null) throw new InboundException("RESOURCE_NOT_FOUND", "入库单不存在");
        var context = new com.lrj.wms.contract.messaging.StockPostingContext(orderId, String.valueOf(order.get("owner_id")),
                String.valueOf(line.get("sku_id")), String.valueOf(line.get("base_unit")), locationId, null, lotId,
                "HOLD", null, null);
        new com.lrj.wms.runtime.messaging.SourceCommandContextStore(session).bind(enterpriseId, warehouseId,
                String.valueOf(result.get("commandId")), context, Boolean.TRUE.equals(result.get("replayed")));
    }

    /** 按设备会话序号恢复，不入账。 */
    public Map<String, Object> getObservation(String enterpriseId, String warehouseId, String deviceId,
            String deviceSessionId, long sequenceNo) {
        Map<String, Object> observation = mapper().getObservation(enterpriseId, warehouseId, deviceId, deviceSessionId,
                sequenceNo);
        if (observation == null) {
            throw new InboundException("RESOURCE_NOT_FOUND", "观察不存在");
        }
        return observationView(observation, true, false);
    }

    /** T3：仅新 inbox 增加 received_posted。 */
    public Map<String, Object> consumeReceive(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        new SourceProtocolService(session, clock).requireResultFact(enterpriseId, warehouseId, commandId, "RECEIVE", lineId);
        if (mapper().lockLine(enterpriseId, warehouseId, lineId) == null) {
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("RESULT_FACT_MISSING");
        }
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            if (mapper().addReceivedPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED",
                    Timestamp.from(clock.instant())) != 1) {
                throw new InboundException("VERSION_CONFLICT", "回执累计与实物数量不一致");
            }
        }
        result.put("line", mapper().lockLine(enterpriseId, warehouseId, lineId));
        return result;
    }

    /** 质检：accepted+rejected=inspected，写入独立质检版本。 */
    public Map<String, Object> inspect(String enterpriseId, String warehouseId, String inspectionId, String lineId,
            BigDecimal accepted, BigDecimal rejected, String actorId, long sourceVersion) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> line = requireLine(mapper(), enterpriseId, warehouseId, lineId);
        BigDecimal inspected = accepted.add(rejected);
        if (inspected.compareTo(decimal(line.get("received_physical_qty"))) > 0) {
            throw new InboundException("INSPECT_EXCEEDS_RECEIVED", "质检超过已收实物");
        }
        if (accepted.compareTo(BigDecimal.ZERO) < 0 || rejected.compareTo(BigDecimal.ZERO) < 0) {
            throw new InboundException("INVALID_QTY", "质检数量不能为负");
        }
        String result = rejected.compareTo(BigDecimal.ZERO) > 0 && accepted.compareTo(BigDecimal.ZERO) == 0
                ? RESULT_REJECTED : RESULT_ACCEPTED;
        try {
            mapper().insertInspection(inspectionId, enterpriseId, warehouseId, lineId, inspected, accepted, rejected, result,
                    sourceVersion, actorId, now);
        } catch (RuntimeException ex) {
            if (!com.lrj.wms.runtime.db.DatabaseErrors.duplicateKey(ex)) throw ex;
            throw new InboundException("DUPLICATE_INSPECTION", "同版本质检已存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inspectionId", inspectionId);
        body.put("resultCode", result);
        body.put("sourceVersion", sourceVersion);
        body.put("skuId", line.get("sku_id"));
        return body;
    }

    /** 上架实物：不超过已收未上架且质检合格量；目标必须是存储位。 */
    public Map<String, Object> putaway(String enterpriseId, String warehouseId, String orderId, String lineId,
            String taskId, String targetLocationId, String targetLocationType, BigDecimal qty, String commandId, String actorId) {
        requireIdentity("操作人", actorId);
        requireIdentity("命令", commandId);
        if (qty == null || qty.signum() <= 0) throw new InboundException("INVALID_QTY", "上架数量必须为正");
        if (targetLocationId == null || targetLocationId.isBlank()) {
            throw new InboundException("INVALID_PUTAWAY_LOCATION", "上架库位不能为空");
        }
        if (!LOCATION_STORAGE.equals(targetLocationType)) {
            throw new InboundException("INVALID_PUTAWAY_LOCATION", "上架目标必须是存储位");
        }
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        Map<String, Object> line = requireLine(mapper, enterpriseId, warehouseId, lineId);
        requireLineOrder(line, orderId);
        var protocol = new SourceProtocolService(session, clock);
        var task = mapper.lockPutawayTask(enterpriseId, warehouseId, taskId);
        if (task != null && (!orderId.equals(task.get("document_id")) || !lineId.equals(task.get("document_line_id"))
                || !targetLocationId.equals(task.get("target_location_id")) || !"PUTAWAY".equals(task.get("task_type"))
                || qty.compareTo(decimal(task.get("planned_qty"))) != 0)) {
            throw new com.lrj.wms.runtime.command.CommandConflictException();
        }
        var replay = protocol.replayIfPresent(SourceProtocolService.ACTION_PUTAWAY, "SUB_ACTION",
                enterpriseId, warehouseId, commandId, orderId, taskId, lineId, qty);
        if (replay != null) {
            if (task == null) throw new InboundException("TASK_MISSING", "历史上架命令缺少对应任务");
            replay.put("taskId", taskId); replay.put("lineId", lineId);
            return replay;
        }
        if (task != null && ("COMPLETED".equals(task.get("state")) || "CANCELLED".equals(task.get("state"))
                || decimal(task.get("completed_qty")).signum() != 0)) {
            throw new InboundException("INVALID_TASK_STATE", "任务不能重复执行");
        }
        if (task != null && task.get("assignee_id") != null && !actorId.equals(task.get("assignee_id"))) {
            throw new InboundException("TASK_ASSIGNEE_MISMATCH", "任务已由其他操作人领取");
        }
        Map<String, Object> inspection = mapper.latestInspection(enterpriseId, warehouseId, lineId);
        if (inspection == null) {
            throw new InboundException("QC_REQUIRED", "上架前必须完成质检");
        }
        if (RESULT_REJECTED.equals(String.valueOf(inspection.get("result_code")))) {
            throw new InboundException("QC_REJECTED", "质检不合格不能上架");
        }
        BigDecimal accepted = decimal(inspection.get("accepted_qty"));
        BigDecimal already = decimal(line.get("putaway_physical_qty"));
        if (already.add(qty).compareTo(accepted) > 0) {
            throw new InboundException("QC_INSUFFICIENT_ACCEPTED", "上架超过质检合格量");
        }
        BigDecimal remain = decimal(line.get("received_physical_qty")).subtract(already);
        if (qty.compareTo(remain) > 0) {
            throw new InboundException("OVER_PUTAWAY", "上架超过已收未上架量");
        }
        // 先确定来源命令是否首发；只有首发才累加实物。全部写入在调用方同一个本库事务。
        Map<String, Object> command = protocol.submitPutaway(enterpriseId, warehouseId,
                commandId, orderId, taskId, lineId, actorId, qty);
        if (!Boolean.TRUE.equals(command.get("replayed"))) {
            if (mapper.addPutawayPhysical(enterpriseId, warehouseId, lineId, qty, now) != 1) {
                throw new InboundException("VERSION_CONFLICT", "上架行更新冲突");
            }
            if (task == null) mapper.insertTask(taskId, enterpriseId, warehouseId, "PUTAWAY", orderId, lineId, null,
                    targetLocationId, qty, "STARTED", now);
            if (mapper.addTaskCompleted(enterpriseId, warehouseId, taskId, qty, "COMPLETED", now) != 1) {
                throw new InboundException("VERSION_CONFLICT", "上架任务更新冲突");
            }
        }
        command.put("taskId", taskId);
        command.put("lineId", lineId);
        return command;
    }

    /** T3：仅新 inbox 增加 putaway_posted。 */
    public Map<String, Object> consumePutaway(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        new SourceProtocolService(session, clock).requireResultFact(enterpriseId, warehouseId, commandId, "PUTAWAY", lineId);
        if (mapper().lockLine(enterpriseId, warehouseId, lineId) == null) {
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("RESULT_FACT_MISSING");
        }
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            if (mapper().addPutawayPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED",
                    Timestamp.from(clock.instant())) != 1) {
                throw new InboundException("VERSION_CONFLICT", "回执累计与实物数量不一致");
            }
        }
        result.put("line", mapper().lockLine(enterpriseId, warehouseId, lineId));
        return result;
    }

    private Map<String, Object> requireLine(InboundReceiptMapper mapper, String enterpriseId, String warehouseId,
            String lineId) {
        Map<String, Object> line = mapper.lockLine(enterpriseId, warehouseId, lineId);
        if (line == null) {
            throw new InboundException("RESOURCE_NOT_FOUND", "入库行不存在");
        }
        return line;
    }

    private InboundReceiptMapper mapper() {
        return session.getMapper(InboundReceiptMapper.class);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static void requireIdentity(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new InboundException("AMBIGUOUS_OBSERVATION", label + "不能为空，已隔离未入账");
        }
    }

    private static String observationDigest(String receiptSessionId, String partId, String lineId, BigDecimal qty) {
        return sha256("RECEIVE\u001f" + receiptSessionId + '\u001f' + partId + '\u001f' + lineId + '\u001f'
                + qty.toPlainString());
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }

    private static Map<String, Object> observationView(Map<String, Object> observation, boolean replayed, boolean reusedPart) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", observation.get("command_id"));
        body.put("effectId", observation.get("business_effect_key"));
        body.put("receiptSessionId", observation.get("receipt_session_id"));
        body.put("partId", observation.get("part_id"));
        body.put("lineId", observation.get("inbound_line_id"));
        body.put("replayed", replayed);
        body.put("reusedPart", reusedPart);
        body.put("state", observation.get("state"));
        return body;
    }
    /** 企业/仓一致还不够，路径中的单据必须真正拥有该行。 */
    private static void requireLineOrder(Map<String, Object> line, String orderId) {
        if (!java.util.Objects.equals(orderId, line.get("order_id"))) {
            throw new InboundException("UNKNOWN_LINE", "入库行不属于该单据");
        }
    }
}
