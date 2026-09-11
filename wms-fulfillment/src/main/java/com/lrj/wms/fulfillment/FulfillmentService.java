package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;

/** attempt/XID/participant 映射。TC 状态只存观察副本，不能本地发明放行决定。 */
public final class FulfillmentService {
    public static final String ORDER_OPEN = "OPEN";
    public static final String ATTEMPT_PLANNED = "PLANNED";
    public static final String ATTEMPT_STARTING = "TCC_STARTING";
    public static final String ATTEMPT_TRYING = "TCC_TRYING";
    public static final String ATTEMPT_ALLOCATED = "ALLOCATED";
    public static final String PARTICIPANT_PLANNED = "PLANNED";
    public static final String PARTICIPANT_CONFIRMED = "CONFIRMED";
    public static final String LAUNCH_CLAIMED = "CLAIMED";
    public static final String LAUNCH_BOUND = "BOUND";
    public static final String CLEANUP_NONE = "NONE";
    public static final String TC_COMMITTED = "Committed";
    public static final String NO_WAREHOUSE = "NO_WAREHOUSE";
    public static final String EVENT_ALLOCATION_COMPLETED = "AllocationCompleted";
    public static final String EVENT_OUTBOUND_ORDER_REQUESTED = "OutboundOrderRequested";
    public static final String EVENT_EXECUTION_AUTHORIZATION_REQUESTED = "ExecutionAuthorizationRequested";
    public static final Duration LAUNCH_LEASE = Duration.ofSeconds(30);

    private final SqlSession session;
    private final Clock clock;

    public FulfillmentService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 按来源单号创建或重放履约单。异摘要拒绝。 */
    public Map<String, Object> createOrder(String enterpriseId, String sourceSystem, String sourceOrderNo,
            String digest, List<Map<String, Object>> lines, long strategyVersion) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(sourceSystem, "INVALID_SOURCE", "来源系统不能为空");
        requireId(sourceOrderNo, "INVALID_SOURCE", "来源单号不能为空");
        requireDigest(digest);
        if (lines == null || lines.isEmpty()) {
            throw new FulfillmentException("INVALID_LINE", "履约行不能为空");
        }
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        String orderId = UUID.randomUUID().toString();
        mapper.insertOrderIgnore(orderId, enterpriseId, sourceSystem, sourceOrderNo, digest, ORDER_OPEN,
                strategyVersion, now);
        Map<String, Object> order = mapper.lockOrderBySource(enterpriseId, sourceSystem, sourceOrderNo);
        if (order == null) {
            throw new FulfillmentException("VERSION_CONFLICT", "履约单创建竞争");
        }
        if (!digest.equals(String.valueOf(order.get("request_digest")))) {
            throw new FulfillmentException("ORDER_CONFLICT", "同源单号请求摘要不一致");
        }
        if (orderId.equals(String.valueOf(order.get("id")))) {
            for (Map<String, Object> line : lines) {
                mapper.insertLine(UUID.randomUUID().toString(), enterpriseId, orderId, required(line, "sourceLineId"),
                        required(line, "skuId"), requiredQty(line.get("requestedQty")), required(line, "baseUnit"),
                        line.get("minRemainingDays") == null ? 0 : ((Number) line.get("minRemainingDays")).intValue(),
                        now);
            }
        }
        return orderView(order);
    }

    /** 创建固定参与者 attempt，并 CAS 绑定为订单活动尝试。未知未终态不得重开。 */
    public Map<String, Object> createAttempt(String enterpriseId, String fulfillmentId, Instant deadline,
            List<String> warehouses, List<Map<String, Object>> participantLines) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(fulfillmentId, "UNKNOWN_ORDER", "履约单不能为空");
        Set<String> uniqueWarehouses = uniqueWarehouses(warehouses);
        if (participantLines == null || participantLines.isEmpty()) {
            throw new FulfillmentException("INVALID_LINE", "参与行不能为空");
        }
        Set<String> linedWarehouses = new LinkedHashSet<>();
        for (Map<String, Object> line : participantLines) {
            String warehouseId = required(line, "warehouseId");
            if (!uniqueWarehouses.contains(warehouseId)) {
                throw new FulfillmentException("INVALID_PARTICIPANT", "参与行仓库不在固定清单中");
            }
            linedWarehouses.add(warehouseId);
        }
        if (!linedWarehouses.containsAll(uniqueWarehouses)) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "每个固定参与仓都必须有行");
        }
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> order = mapper.lockOrder(enterpriseId, fulfillmentId);
        if (order == null) {
            throw new FulfillmentException("UNKNOWN_ORDER", "履约单不存在");
        }
        String active = nullable(order.get("active_attempt_id"));
        if (active != null) {
            Map<String, Object> current = mapper.lockAttempt(enterpriseId, active);
            if (current == null || !isTerminalFailure(String.valueOf(current.get("state")))) {
                throw new FulfillmentException("ATTEMPT_IN_PROGRESS", "活动attempt未知或未终态，不能重开");
            }
        }
        freezeAgainstOrder(mapper, enterpriseId, fulfillmentId, uniqueWarehouses, participantLines);
        String hash = participantHash(uniqueWarehouses);
        String digest = AllocationPlan.digest(participantLines);
        String attemptId = UUID.randomUUID().toString();
        if (deadline == null) {
            throw new FulfillmentException("INVALID_DEADLINE", "截止时刻不能为空");
        }
        mapper.insertAttempt(attemptId, enterpriseId, fulfillmentId, ATTEMPT_PLANNED, Timestamp.from(deadline),
                hash, digest, now);
        for (String warehouseId : uniqueWarehouses) {
            String participantId = UUID.randomUUID().toString();
            mapper.insertParticipant(participantId, enterpriseId, attemptId, warehouseId, PARTICIPANT_PLANNED, now);
            for (Map<String, Object> line : participantLines) {
                if (!warehouseId.equals(String.valueOf(line.get("warehouseId")))) {
                    continue;
                }
                mapper.insertParticipantLine(UUID.randomUUID().toString(), enterpriseId, participantId,
                        required(line, "orderLineId"), required(line, "skuId"), requiredQty(line.get("qty")),
                        required(line, "baseUnit"), now);
            }
        }
        if (mapper.casActiveAttempt(enterpriseId, fulfillmentId, attemptId, active,
                ((Number) order.get("version")).longValue(), now) != 1) {
            throw new FulfillmentException("VERSION_CONFLICT", "活动attempt CAS失败");
        }
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** CAS 领取启动权。失败者不得 begin/Try。同执行器同代际重放。 */
    public Map<String, Object> claimLaunch(String enterpriseId, String attemptId, String executorId) {
        requireId(executorId, "INVALID_EXECUTOR", "执行器不能为空");
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        refuseExpiredTry(attempt);
        String xid = nullable(attempt.get("xid"));
        String owner = nullable(attempt.get("launch_owner"));
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        if (xid != null) {
            if (executorId.equals(owner)) {
                return attemptView(attempt, mapper.lockParticipants(enterpriseId, attemptId));
            }
            throw new FulfillmentException("XID_ALREADY_BOUND", "attempt已绑定XID，不能再领取启动");
        }
        if (ATTEMPT_STARTING.equals(String.valueOf(attempt.get("state"))) && executorId.equals(owner)) {
            return attemptView(attempt, mapper.lockParticipants(enterpriseId, attemptId));
        }
        if (mapper.claimLaunch(enterpriseId, attemptId, executorId, Timestamp.from(clock.instant().plus(LAUNCH_LEASE)),
                epoch, ((Number) attempt.get("version")).longValue(), ATTEMPT_STARTING, now) != 1) {
            throw new FulfillmentException("LAUNCH_CAS_LOST", "启动权已被其他执行器领取");
        }
        mapper.insertLaunch(UUID.randomUUID().toString(), enterpriseId, attemptId, epoch + 1, executorId,
                LAUNCH_CLAIMED, CLEANUP_NONE, now);
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 绑定 XID 一次。覆盖拒绝；并发只允许一个胜者。 */
    public Map<String, Object> bindXid(String enterpriseId, String attemptId, String executorId, String xid) {
        requireId(executorId, "INVALID_EXECUTOR", "执行器不能为空");
        xid = requireXid(xid);
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        refuseExpiredTry(attempt);
        String bound = nullable(attempt.get("xid"));
        String owner = nullable(attempt.get("launch_owner"));
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        if (bound != null) {
            if (bound.equals(xid) && executorId.equals(owner)) {
                return attemptView(attempt, mapper.lockParticipants(enterpriseId, attemptId));
            }
            throw new FulfillmentException("XID_ALREADY_BOUND", "XID绑定后不可覆盖");
        }
        if (!executorId.equals(owner)) {
            throw new FulfillmentException("LAUNCH_OWNER_MISMATCH", "只有领取启动权的执行器可绑定XID");
        }
        try {
            if (mapper.bindXid(enterpriseId, attemptId, xid, executorId, epoch, ATTEMPT_TRYING, now) != 1) {
                throw new FulfillmentException("LAUNCH_CAS_LOST", "XID绑定竞争失败");
            }
        } catch (PersistenceException error) {
            if (isDuplicate(error)) {
                throw new FulfillmentException("XID_CONFLICT", "同一XID不能绑定到两个attempt");
            }
            throw error;
        }
        mapper.bindLaunch(enterpriseId, attemptId, epoch, executorId, xid, LAUNCH_BOUND, now);
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 写入 TC 观察副本，不据此直接放行。 */
    public Map<String, Object> observeTc(String enterpriseId, String attemptId, String observedStatus, String evidence) {
        requireId(observedStatus, "INVALID_TC_STATUS", "TC观察状态不能为空");
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        if (ATTEMPT_ALLOCATED.equals(String.valueOf(attempt.get("state")))
                && (!TC_COMMITTED.equals(observedStatus) || evidence == null || evidence.isBlank())) {
            throw new FulfillmentException("ALLOCATED_IMMUTABLE", "已ALLOCATED不能用缺证据观察覆盖");
        }
        mapper.observeTc(enterpriseId, attemptId, observedStatus, evidence, now);
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 绑定仓级XID/branch/action一次。fulfillment只观察，不驱动Confirm/Cancel。 */
    public Map<String, Object> bindParticipant(String enterpriseId, String attemptId, String warehouseId,
            String xid, long branchId, String actionName, String reservationId, long routeEpoch, String branchState) {
        requireId(warehouseId, "INVALID_PARTICIPANT", "仓库不能为空");
        requireId(actionName, "INVALID_PARTICIPANT", "TCC动作名不能为空");
        requireId(branchState, "INVALID_PARTICIPANT", "分支观察状态不能为空");
        xid = requireXid(xid);
        if (branchId <= 0 || routeEpoch < 0) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "branchId必须为正且routeEpoch不能为负");
        }
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        refuseExpiredTry(attempt);
        String bound = nullable(attempt.get("xid"));
        if (bound == null) {
            throw new FulfillmentException("XID_NOT_BOUND", "attempt尚未绑定XID，不能登记仓分支");
        }
        if (!bound.equals(xid)) {
            throw new FulfillmentException("XID_MISMATCH", "仓级XID必须与attempt绑定XID一致");
        }
        List<Map<String, Object>> participants = mapper.lockParticipants(enterpriseId, attemptId);
        boolean found = false;
        for (Map<String, Object> participant : participants) {
            if (warehouseId.equals(String.valueOf(participant.get("warehouse_id")))) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "参与仓不存在");
        }
        if (mapper.bindParticipantBranch(enterpriseId, attemptId, warehouseId, xid, branchId, actionName,
                reservationId, routeEpoch, branchState, now()) != 1) {
            throw new FulfillmentException("BRANCH_ALREADY_BOUND", "仓级分支身份不可改绑");
        }
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 记录仓级 RM 观察状态，不是 TC 决定。 */
    public Map<String, Object> observeParticipant(String enterpriseId, String attemptId, String warehouseId,
            String state, Long confirmedVersion) {
        requireId(warehouseId, "INVALID_PARTICIPANT", "仓库不能为空");
        requireId(state, "INVALID_PARTICIPANT", "仓级状态不能为空");
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        requireAttempt(mapper, enterpriseId, attemptId);
        if (mapper.observeParticipant(enterpriseId, attemptId, warehouseId, state, confirmedVersion, now()) != 1) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "参与仓不存在");
        }
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 已绑定 XID 后生成下游 Try 头；禁止空上下文。 */
    public Map<String, String> tryHeaders(String enterpriseId, String attemptId) {
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        refuseExpiredTry(attempt);
        Map<String, String> headers = TryPropagation.headers(nullable(attempt.get("xid")));
        TryPropagation.requireMatch(headers, nullable(attempt.get("xid")));
        return headers;
    }

    /**
     * 仅当 TC Committed 证据与全部仓 CONFIRMED 同时满足才写 ALLOCATED，并在同一本地事务写建单/执行授权 Outbox。
     * 已 ALLOCATED 则只补齐缺失 Outbox。不在 TCC 事务内派发设备。
     */
    public Map<String, Object> markAllocated(String enterpriseId, String attemptId) {
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        List<Map<String, Object>> participants = mapper.lockParticipants(enterpriseId, attemptId);
        if (ATTEMPT_ALLOCATED.equals(String.valueOf(attempt.get("state")))) {
            writeBarrierOutbox(mapper, enterpriseId, attemptId, attempt, participants);
            return attemptView(attempt, participants);
        }
        String observed = nullable(attempt.get("tc_observed_status"));
        String evidence = nullable(attempt.get("tc_terminal_evidence"));
        if (!TC_COMMITTED.equals(observed) || evidence == null || evidence.isBlank()) {
            throw new FulfillmentException("ALLOCATED_EVIDENCE_MISSING", "缺少TC终态证据，拒绝ALLOCATED");
        }
        if (participants.isEmpty()) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "attempt没有固定参与者");
        }
        for (Map<String, Object> participant : participants) {
            if (!PARTICIPANT_CONFIRMED.equals(String.valueOf(participant.get("state")))) {
                throw new FulfillmentException("PARTICIPANTS_NOT_CONFIRMED", "固定参与者尚未全部CONFIRMED");
            }
        }
        if (mapper.casAttemptState(enterpriseId, attemptId, ATTEMPT_ALLOCATED, ATTEMPT_TRYING, now()) != 1
                && mapper.casAttemptState(enterpriseId, attemptId, ATTEMPT_ALLOCATED, "TCC_COMPLETING", now()) != 1) {
            throw new FulfillmentException("VERSION_CONFLICT", "ALLOCATED状态竞争");
        }
        Map<String, Object> allocated = mapper.lockAttempt(enterpriseId, attemptId);
        writeBarrierOutbox(mapper, enterpriseId, attemptId, allocated, participants);
        return attemptView(allocated, participants);
    }

    /** 已绑 XID 且未放行的 attempt，供 XXL 同步观察。 */
    public List<Map<String, Object>> listOpenBoundAttempts(String enterpriseId) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        return session.getMapper(FulfillmentMapper.class).listOpenBoundAttempts(enterpriseId);
    }

    /** 扫描已具备证据的 attempt，补齐 ALLOCATED 与屏障 Outbox。跳过仍缺确认的项。 */
    public int recoverReadyBarriers(String enterpriseId) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        int recovered = 0;
        for (String attemptId : mapper.listReadyBarrierAttempts(enterpriseId)) {
            try {
                markAllocated(enterpriseId, attemptId);
                recovered++;
            } catch (FulfillmentException error) {
                if (!isRecoveryPending(error)) {
                    throw error;
                }
            }
        }
        return recovered;
    }

    /** 缺证据或参与者未确认时的稳定拒绝码，供调用方进入RECOVERY_PENDING。 */
    public static boolean isRecoveryPending(FulfillmentException error) {
        return "ALLOCATED_EVIDENCE_MISSING".equals(error.code())
                || "PARTICIPANTS_NOT_CONFIRMED".equals(error.code());
    }

    /** 固定参与仓集合的稳定摘要。 */
    public static String participantHash(Set<String> warehouses) {
        String payload = String.join("\u001f", warehouses);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void freezeAgainstOrder(FulfillmentMapper mapper, String enterpriseId, String fulfillmentId,
            Set<String> warehouses, List<Map<String, Object>> participantLines) {
        List<Map<String, Object>> orderLines = mapper.lockLines(enterpriseId, fulfillmentId);
        if (orderLines.isEmpty()) {
            throw new FulfillmentException("INVALID_LINE", "履约行不存在，不能冻结分配");
        }
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> line : orderLines) {
            bySource.put(String.valueOf(line.get("source_line_id")), line);
        }
        Map<String, BigDecimal> assigned = new LinkedHashMap<>();
        for (Map<String, Object> line : participantLines) {
            String orderLineId = required(line, "orderLineId");
            Map<String, Object> orderLine = bySource.get(orderLineId);
            if (orderLine == null) {
                throw new FulfillmentException("INVALID_LINE", "参与行不是履约行");
            }
            if (!String.valueOf(orderLine.get("sku_id")).equals(required(line, "skuId"))
                    || !String.valueOf(orderLine.get("base_unit")).equals(required(line, "baseUnit"))) {
                throw new FulfillmentException("QTY_MISMATCH", "参与行SKU或单位与履约行不一致");
            }
            if (!warehouses.contains(required(line, "warehouseId"))) {
                throw new FulfillmentException("INVALID_PARTICIPANT", "参与行仓库不在固定清单中");
            }
            assigned.merge(orderLineId, requiredQty(line.get("qty")), BigDecimal::add);
        }
        if (assigned.size() != bySource.size()) {
            throw new FulfillmentException("QTY_MISMATCH", "每个履约行都必须有冻结数量");
        }
        for (Map.Entry<String, Map<String, Object>> entry : bySource.entrySet()) {
            BigDecimal requested = requiredQty(entry.getValue().get("requested_qty"));
            BigDecimal total = assigned.get(entry.getKey());
            if (requested == null || total == null || requested.compareTo(total) != 0) {
                throw new FulfillmentException("QTY_MISMATCH", "冻结数量必须等于履约行请求数量");
            }
        }
    }

    private void refuseExpiredTry(Map<String, Object> attempt) {
        Instant deadline = toInstant(attempt.get("deadline"));
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            throw new FulfillmentException("TRY_DEADLINE_EXCEEDED", "超过有界Try截止，不能再发起或传播");
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("不支持的截止时刻类型: " + value.getClass().getName());
    }

    private Map<String, Object> requireAttempt(FulfillmentMapper mapper, String enterpriseId, String attemptId) {
        requireId(attemptId, "UNKNOWN_ATTEMPT", "attempt不能为空");
        Map<String, Object> attempt = mapper.lockAttempt(enterpriseId, attemptId);
        if (attempt == null) {
            throw new FulfillmentException("UNKNOWN_ATTEMPT", "attempt不存在");
        }
        return attempt;
    }

    private void writeBarrierOutbox(FulfillmentMapper mapper, String enterpriseId, String attemptId,
            Map<String, Object> attempt, List<Map<String, Object>> participants) {
        Timestamp now = now();
        String xid = nullable(attempt.get("xid"));
        String evidence = nullable(attempt.get("tc_terminal_evidence"));
        String warehouses = String.join(",", participants.stream()
                .map(participant -> String.valueOf(participant.get("warehouse_id"))).toList());
        insertOutbox(mapper, enterpriseId, attemptId, NO_WAREHOUSE, EVENT_ALLOCATION_COMPLETED,
                "{\"attemptId\":\"" + escape(attemptId) + "\",\"xid\":\"" + escape(xid)
                        + "\",\"tcTerminalEvidence\":\"" + escape(evidence) + "\",\"warehouses\":\""
                        + escape(warehouses) + "\"}", now);
        List<Map<String, Object>> lines = mapper.listParticipantLines(enterpriseId, attemptId);
        for (Map<String, Object> participant : participants) {
            String warehouseId = String.valueOf(participant.get("warehouse_id"));
            String reservationId = nullable(participant.get("reservation_id"));
            String payload = "{\"attemptId\":\"" + escape(attemptId) + "\",\"xid\":\"" + escape(xid)
                    + "\",\"warehouseId\":\"" + escape(warehouseId) + "\",\"reservationId\":\""
                    + escape(reservationId) + "\",\"lines\":" + linesJson(lines, warehouseId) + "}";
            insertOutbox(mapper, enterpriseId, attemptId, warehouseId, EVENT_OUTBOUND_ORDER_REQUESTED, payload, now);
            insertOutbox(mapper, enterpriseId, attemptId, warehouseId, EVENT_EXECUTION_AUTHORIZATION_REQUESTED,
                    payload, now);
        }
    }

    private static void insertOutbox(FulfillmentMapper mapper, String enterpriseId, String attemptId,
            String warehouseId, String eventType, String payload, Timestamp now) {
        mapper.insertOutboxIgnore(UUID.randomUUID().toString(), enterpriseId, attemptId, warehouseId, eventType,
                operationId(eventType, attemptId, warehouseId), payload, now);
    }

    private static String linesJson(List<Map<String, Object>> lines, String warehouseId) {
        StringBuilder body = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> line : lines) {
            if (!warehouseId.equals(String.valueOf(line.get("warehouse_id")))) {
                continue;
            }
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append("{\"orderLineId\":\"").append(escape(line.get("order_line_id")))
                    .append("\",\"skuId\":\"").append(escape(line.get("sku_id")))
                    .append("\",\"qty\":\"").append(escape(line.get("qty")))
                    .append("\",\"baseUnit\":\"").append(escape(line.get("base_unit"))).append("\"}");
        }
        return body.append(']').toString();
    }

    private static String operationId(String eventType, String attemptId, String warehouseId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (eventType + '\u001f' + attemptId + '\u001f' + warehouseId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static boolean isTerminalFailure(String state) {
        return "CANCELLED".equals(state) || "FAILED".equals(state);
    }

    private static Set<String> uniqueWarehouses(List<String> warehouses) {
        if (warehouses == null || warehouses.isEmpty()) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "参与仓不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String warehouseId : warehouses) {
            requireId(warehouseId, "INVALID_PARTICIPANT", "仓库不能为空");
            if (!unique.add(warehouseId)) {
                throw new FulfillmentException("INVALID_PARTICIPANT", "同一attempt不能重复仓");
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(String::compareTo);
        return new LinkedHashSet<>(sorted);
    }

    private static Map<String, Object> orderView(Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.get("id"));
        body.put("status", order.get("status"));
        body.put("requestDigest", order.get("request_digest"));
        body.put("activeAttemptId", order.get("active_attempt_id"));
        body.put("strategyVersion", order.get("strategy_version"));
        return body;
    }

    private static Map<String, Object> attemptView(Map<String, Object> attempt, List<Map<String, Object>> participants) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", attempt.get("id"));
        body.put("fulfillmentId", attempt.get("fulfillment_id"));
        body.put("state", attempt.get("state"));
        body.put("xid", attempt.get("xid"));
        body.put("tcObservedStatus", attempt.get("tc_observed_status"));
        body.put("tcTerminalEvidence", attempt.get("tc_terminal_evidence"));
        body.put("participantSetHash", attempt.get("participant_set_hash"));
        body.put("allocationDigest", attempt.get("allocation_digest"));
        body.put("launchEpoch", attempt.get("launch_epoch"));
        body.put("launchOwner", attempt.get("launch_owner"));
        List<String> warehouses = new ArrayList<>();
        List<Map<String, Object>> branches = new ArrayList<>();
        for (Map<String, Object> participant : participants) {
            warehouses.add(String.valueOf(participant.get("warehouse_id")));
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("warehouseId", participant.get("warehouse_id"));
            branch.put("xid", participant.get("xid"));
            branch.put("branchId", participant.get("branch_id"));
            branch.put("actionName", participant.get("action_name"));
            branch.put("state", participant.get("state"));
            branches.add(branch);
        }
        body.put("warehouses", warehouses);
        body.put("participants", branches);
        return body;
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new FulfillmentException("INVALID_DIGEST", "请求摘要必须是SHA-256十六进制");
        }
    }

    private static String requireXid(String xid) {
        if (xid == null || xid.isBlank() || xid.length() > 128) {
            throw new FulfillmentException("INVALID_XID", "XID长度必须为1..128");
        }
        return xid.trim();
    }

    private static void requireId(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new FulfillmentException(code, message);
        }
    }

    private static String required(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new FulfillmentException("INVALID_LINE", "缺少" + key);
        }
        return String.valueOf(value);
    }

    private static BigDecimal requiredQty(Object value) {
        if (value == null) {
            throw new FulfillmentException("INVALID_LINE", "数量不能为空");
        }
        BigDecimal qty = value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
        if (qty.signum() <= 0) {
            throw new FulfillmentException("INVALID_LINE", "数量必须为正");
        }
        return qty;
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isDuplicate(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
