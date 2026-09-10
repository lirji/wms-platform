package com.lrj.wms.inbound.receipt;

import com.lrj.wms.inbound.protocol.SourceProtocolService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
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

    private final SqlSession session;
    private final Clock clock;

    public InboundReceiptService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 创建入库单与行。同外部单号冲突由唯一键拒绝。 */
    public Map<String, Object> createOrder(String enterpriseId, String warehouseId, String orderId, String externalSource,
            String externalNo, String ownerId, List<Map<String, Object>> lines) {
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        try {
            mapper.insertOrder(orderId, enterpriseId, warehouseId, externalSource, externalNo, ownerId, STATUS_APPROVED, now);
        } catch (RuntimeException ex) {
            throw new InboundException("DUPLICATE_DOCUMENT", "入库单已存在");
        }
        for (Map<String, Object> line : lines) {
            mapper.insertLine(String.valueOf(line.get("lineId")), enterpriseId, warehouseId, orderId,
                    String.valueOf(line.get("externalLineId")), String.valueOf(line.get("skuId")),
                    decimal(line.get("expectedQty")), String.valueOf(line.getOrDefault("unit", "EA")), now);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", STATUS_APPROVED);
        return body;
    }

    /** 收货实物：校验剩余额度后累计 physical，并提交 RECEIVE 来源命令。 */
    public Map<String, Object> receive(String enterpriseId, String warehouseId, String orderId, String lineId,
            String commandId, String partId, String actorId, BigDecimal qty) {
        Timestamp now = Timestamp.from(clock.instant());
        InboundReceiptMapper mapper = mapper();
        Map<String, Object> line = requireLine(mapper, enterpriseId, warehouseId, lineId);
        BigDecimal physical = decimal(line.get("received_physical_qty")).add(qty);
        if (physical.compareTo(decimal(line.get("expected_qty")).subtract(decimal(line.get("closed_qty")))) > 0) {
            throw new InboundException("OVER_RECEIVE", "收货超过剩余额度");
        }
        mapper.addReceivedPhysical(enterpriseId, warehouseId, lineId, qty, now);
        mapper.updateOrderStatus(enterpriseId, warehouseId, orderId, STATUS_RECEIVING, now);
        return new SourceProtocolService(session, clock).submitReceive(enterpriseId, warehouseId, commandId, orderId, partId,
                lineId, actorId, qty);
    }

    /** T3：仅新 inbox 增加 received_posted。 */
    public Map<String, Object> consumeReceive(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            mapper().addReceivedPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED",
                    Timestamp.from(clock.instant()));
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
            throw new InboundException("DUPLICATE_INSPECTION", "同版本质检已存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inspectionId", inspectionId);
        body.put("resultCode", result);
        body.put("sourceVersion", sourceVersion);
        body.put("skuId", line.get("sku_id"));
        return body;
    }

    /** 上架实物：不超过已收未上架实物，写 PUTAWAY 任务。库存命令由调用方随后提交。 */
    public Map<String, Object> putaway(String enterpriseId, String warehouseId, String orderId, String lineId,
            String taskId, String targetLocationId, BigDecimal qty) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> line = requireLine(mapper(), enterpriseId, warehouseId, lineId);
        BigDecimal remain = decimal(line.get("received_physical_qty")).subtract(decimal(line.get("putaway_physical_qty")));
        if (qty.compareTo(remain) > 0) {
            throw new InboundException("OVER_PUTAWAY", "上架超过已收未上架量");
        }
        mapper().addPutawayPhysical(enterpriseId, warehouseId, lineId, qty, now);
        mapper().insertTask(taskId, enterpriseId, warehouseId, "PUTAWAY", orderId, lineId, null, targetLocationId, qty,
                "STARTED", now);
        mapper().addTaskCompleted(enterpriseId, warehouseId, taskId, qty, "COMPLETED", now);
        Map<String, Object> command = new SourceProtocolService(session, clock).submitPutaway(enterpriseId, warehouseId,
                taskId, orderId, taskId, lineId, "SYSTEM", qty);
        command.put("taskId", taskId);
        command.put("lineId", lineId);
        return command;
    }

    /** T3：仅新 inbox 增加 putaway_posted。 */
    public Map<String, Object> consumePutaway(String enterpriseId, String warehouseId, String lineId, String eventId,
            String commandId, String resultState, String postingId, BigDecimal postedQty) {
        Map<String, Object> result = new SourceProtocolService(session, clock).consumeResult(enterpriseId, warehouseId,
                eventId, commandId, resultState, postingId, postedQty);
        if (Boolean.TRUE.equals(result.get("consumed")) && "APPLIED".equals(resultState)) {
            mapper().addPutawayPosted(enterpriseId, warehouseId, lineId, postedQty, "POSTED",
                    Timestamp.from(clock.instant()));
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
}
