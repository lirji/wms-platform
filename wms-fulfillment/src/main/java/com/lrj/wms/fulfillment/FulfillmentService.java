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
    public static final String LAUNCH_UNKNOWN = "UNKNOWN";
    public static final String CLEANUP_NONE = "NONE";
    public static final String CLEANUP_PENDING = "PENDING";
    public static final String CLEANUP_CLEANED = "CLEANED";
    public static final String CLEANUP_WAITING_TIMEOUT = "WAITING_TIMEOUT";
    public static final String TC_COMMITTED = "Committed";
    public static final String NO_WAREHOUSE = "NO_WAREHOUSE";
    public static final String EVENT_ALLOCATION_COMPLETED = "AllocationCompleted";
    public static final String EVENT_OUTBOUND_ORDER_REQUESTED = "OutboundOrderRequested";
    public static final String EVENT_EXECUTION_AUTHORIZATION_REQUESTED = "ExecutionAuthorizationRequested";
    public static final Duration LAUNCH_LEASE = Duration.ofSeconds(30);

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final SqlSession session;
    private final Clock clock;

    public FulfillmentService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> get(String enterpriseId, String fulfillmentId) {
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> order = mapper.lockOrder(enterpriseId, fulfillmentId);
        if (order == null) {
            throw new FulfillmentException("RESOURCE_NOT_FOUND", "履约单不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fulfillmentId", order.get("id"));
        body.put("id", order.get("id"));
        body.put("status", order.get("status"));
        body.put("ownerId", order.get("owner_id"));
        body.put("sourceSystem", order.get("source_system"));
        body.put("sourceOrderNo", order.get("source_order_no"));
        body.put("activeAttemptId", order.get("active_attempt_id"));
        body.put("version", order.get("version"));
        body.put("lines", mapper.lockLines(enterpriseId, fulfillmentId));
        Object attemptId = order.get("active_attempt_id");
        if (attemptId != null && !String.valueOf(attemptId).isBlank()) {
            Map<String, Object> attempt = mapper.lockAttempt(enterpriseId, String.valueOf(attemptId));
            body.put("attemptState", attempt == null ? null : attempt.get("state"));
            body.put("tcObservedStatus", attempt == null ? null : attempt.get("tc_observed_status"));
            body.put("cancelRequested", attempt != null && cancelRequested(attempt.get("cancel_requested")));
            body.put("participants", mapper.listParticipants(enterpriseId, String.valueOf(attemptId)));
        } else {
            body.put("cancelRequested", false);
            body.put("participants", List.of());
        }
        return body;
    }

    /**
     * 受理取消。只打 cancel_requested，不改 attempt 业务态，不发明 ALLOCATED 或 TC Cancel。
     */
    public Map<String, Object> requestCancel(String enterpriseId, String fulfillmentId, String clientOperationId,
            String reason, Long expectedVersion, String actorId) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(fulfillmentId, "RESOURCE_NOT_FOUND", "履约单不能为空");
        requireId(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        requireId(actorId, "INVALID_ACTOR", "操作人不能为空");
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        FulfillmentCancelMapper cancels = session.getMapper(FulfillmentCancelMapper.class);
        Map<String, Object> existing = cancels.getByKey(enterpriseId, clientOperationId);
        if (existing != null) {
            if (!fulfillmentId.equals(String.valueOf(existing.get("fulfillment_id")))) {
                throw new FulfillmentException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键取消内容不一致");
            }
            return cancelView(existing, mapper.lockOrder(enterpriseId, fulfillmentId));
        }
        Map<String, Object> order = mapper.lockOrder(enterpriseId, fulfillmentId);
        if (order == null) {
            throw new FulfillmentException("RESOURCE_NOT_FOUND", "履约单不存在");
        }
        if (expectedVersion != null && expectedVersion.longValue() != ((Number) order.get("version")).longValue()) {
            throw new FulfillmentException("VERSION_CONFLICT", "履约单版本冲突");
        }
        String attemptId = nullable(order.get("active_attempt_id"));
        Timestamp now = now();
        cancels.insertIgnore(UUID.randomUUID().toString(), enterpriseId, fulfillmentId, clientOperationId, attemptId,
                reason, actorId, "CANCEL_REQUESTED", now);
        if (attemptId != null) {
            cancels.casCancelRequested(enterpriseId, attemptId, now);
        }
        return cancelView(cancels.getByKey(enterpriseId, clientOperationId), mapper.lockOrder(enterpriseId, fulfillmentId));
    }

    public List<Map<String, Object>> list(String enterpriseId, int limit) {
        return session.getMapper(FulfillmentMapper.class).listOrders(enterpriseId, limit);
    }

    /** 按来源单号创建或重放履约单。异摘要拒绝。 */
    public Map<String, Object> createOrder(String enterpriseId, String sourceSystem, String sourceOrderNo,
            String digest, List<Map<String, Object>> lines, long strategyVersion) {
        return createOrder(enterpriseId, sourceSystem, sourceOrderNo, digest, lines, strategyVersion, null);
    }

    /** 新客户端显式提供货主；同源重放必须匹配全部原事实，不能依赖客户端自报摘要。 */
    public Map<String, Object> createOrder(String enterpriseId, String sourceSystem, String sourceOrderNo,
            String digest, List<Map<String, Object>> lines, long strategyVersion, String ownerId) {
        if (ownerId != null) requireId(ownerId, "INVALID_OWNER", "货主不能为空");
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(sourceSystem, "INVALID_SOURCE", "来源系统不能为空");
        requireId(sourceOrderNo, "INVALID_SOURCE", "来源单号不能为空");
        requireDigest(digest);
        if (lines == null || lines.isEmpty() || lines.size() > 200) {
            throw new FulfillmentException("INVALID_LINE", "履约行必须非空且不超过200条");
        }
        var requestedLines = canonicalOrderLines(lines, false);
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        String orderId = UUID.randomUUID().toString();
        mapper.insertOrderIgnore(orderId, enterpriseId, sourceSystem, sourceOrderNo, digest, ORDER_OPEN,
                strategyVersion, ownerId, now);
        Map<String, Object> order = mapper.lockOrderBySource(enterpriseId, sourceSystem, sourceOrderNo);
        if (order == null) {
            throw new FulfillmentException("VERSION_CONFLICT", "履约单创建竞争");
        }
        if (!digest.equals(String.valueOf(order.get("request_digest")))
                || !java.util.Objects.equals(ownerId, order.get("owner_id"))
                || strategyVersion != ((Number) order.get("strategy_version")).longValue()) {
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
        if (!requestedLines.equals(canonicalOrderLines(mapper.lockLines(enterpriseId, String.valueOf(order.get("id"))), true)))
            throw new FulfillmentException("ORDER_CONFLICT", "同源单号货主或订单行内容不一致");
        return orderView(order);
    }

    /** 按原行号规范化，数值等值可重放，重复行与非法保质期在写库前拒绝。 */
    private static Map<String, List<Object>> canonicalOrderLines(List<Map<String,Object>> lines, boolean stored) {
        var result = new java.util.TreeMap<String, List<Object>>();
        for (var line : lines) {
            String id = required(line, stored ? "source_line_id" : "sourceLineId");
            String sku = required(line, stored ? "sku_id" : "skuId");
            String unit = required(line, stored ? "base_unit" : "baseUnit");
            BigDecimal qty = requiredQty(line.get(stored ? "requested_qty" : "requestedQty")).stripTrailingZeros();
            Object rawDays = line.get(stored ? "min_remaining_days" : "minRemainingDays");
            int days = rawDays == null ? 0 : new BigDecimal(rawDays.toString()).intValueExact();
            if (days < 0 || result.put(id, List.of(sku, unit, qty, days)) != null)
                throw new FulfillmentException("INVALID_LINE", "行号重复或保质期条件无效");
        }
        return result;
    }

    /** 创建命令与attempt原子落库；省略截止时刻时仅首次取默认值，重试不得延长期限。 */
    public Map<String,Object> prepareAttempt(String enterpriseId, String fulfillmentId, String commandId,
            Instant requestedDeadline, List<String> warehouses, List<Map<String,Object>> lines) {
        requireId(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        requireId(fulfillmentId, "UNKNOWN_ORDER", "履约单不能为空");
        requireId(commandId, "INVALID_ARGUMENT", "命令键不能为空");
        var fixedWarehouses = uniqueWarehouses(warehouses);
        if (fixedWarehouses.size() > 200 || lines == null || lines.isEmpty() || lines.size() > 200)
            throw new FulfillmentException("INVALID_LINE", "参与仓及行须在1到200范围内");
        // JSON数组保留字段边界，规范数量和排序；不能靠可碰撞的分隔字符串辨别不同请求。
        var canonicalLines = lines.stream().map(line -> List.of(required(line,"warehouseId"),
                required(line,"orderLineId"), required(line,"skuId"),
                requiredQty(line.get("qty")).stripTrailingZeros().toPlainString(), required(line,"baseUnit")))
                .sorted(java.util.Comparator.comparing(JSON::writeValueAsString)).toList();
        String hash = com.lrj.wms.runtime.messaging.RuntimeMessage.hash(JSON.writeValueAsString(
                java.util.Arrays.asList("attempt-command-v1", fulfillmentId, requestedDeadline == null ? null : requestedDeadline.toString(),
                        fixedWarehouses, canonicalLines)));
        var mapper = session.getMapper(FulfillmentMapper.class);
        String claim = UUID.randomUUID().toString();
        mapper.insertAttemptCommand(enterpriseId, commandId, fulfillmentId, hash, claim, now());
        var receipt = mapper.lockAttemptCommand(enterpriseId, commandId);
        if (receipt == null || !fulfillmentId.equals(receipt.get("fulfillment_id")) || !hash.equals(receipt.get("payload_hash")))
            throw new FulfillmentException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键分配请求内容不一致");
        Map<String,Object> result;
        if (!claim.equals(receipt.get("claim_id"))) {
            String original = nullable(receipt.get("attempt_id"));
            if (original == null) throw new FulfillmentException("ATTEMPT_RECEIPT_INCOMPLETE", "命令缺少原attempt，不能重建");
            var attempt = requireAttempt(mapper, enterpriseId, original);
            if (!fulfillmentId.equals(attempt.get("fulfillment_id")))
                throw new FulfillmentException("ATTEMPT_RECEIPT_INCOMPLETE", "命令与原attempt不一致");
            result = attemptView(attempt, mapper.lockParticipants(enterpriseId, original));
        } else {
            result = createAttempt(enterpriseId, fulfillmentId,
                    requestedDeadline == null ? clock.instant().plusSeconds(3600) : requestedDeadline,
                    new ArrayList<>(fixedWarehouses), lines);
            if (mapper.bindAttemptCommand(enterpriseId, commandId, claim, String.valueOf(result.get("id"))) != 1)
                throw new FulfillmentException("VERSION_CONFLICT", "分配命令回执绑定失败");
        }
        result.put("clientOperationId", commandId);
        return result;
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

    /** TM在原XID绑定事务内记录审计来源；未绑定的历史attempt不按新配置自动补证据。 */
    public Map<String, Object> bindXid(String enterpriseId, String attemptId, String executorId,
            String xid, TcEvidenceScope scope) {
        Map<String, Object> view = bindXid(enterpriseId, attemptId, executorId, xid);
        long epoch = ((Number) view.get("launchEpoch")).longValue();
        var mapper = session.getMapper(AllocationRecoveryMapper.class);
        mapper.bind(enterpriseId, attemptId, xid, epoch, scope, now());
        var binding = mapper.binding(enterpriseId, attemptId);
        if (binding == null || !xid.equals(binding.get("xid"))
                || epoch != ((Number) binding.get("launch_epoch")).longValue()
                || !scope.clusterId().equals(binding.get("cluster_id"))
                || !scope.applicationId().equals(binding.get("application_id"))
                || !scope.transactionGroup().equals(binding.get("transaction_group"))) {
            throw new FulfillmentException("TC_BINDING_CONFLICT", "原TC审计来源不可改绑，跨企业复用XID也拒绝");
        }
        return view;
    }

    /** begin/绑定失联：未绑定 XID 时把当前启动标 UNKNOWN，租约过期不能单独推断。 */
    public Map<String, Object> markLaunchUnknown(String enterpriseId, String attemptId) {
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        if (nullable(attempt.get("xid")) != null) {
            throw new FulfillmentException("XID_ALREADY_BOUND", "已绑定XID只能恢复原事务，不能标空启动");
        }
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        if (mapper.markLaunchUnknown(enterpriseId, attemptId, epoch, LAUNCH_UNKNOWN, CLEANUP_PENDING, now()) != 1) {
            throw new FulfillmentException("LAUNCH_NOT_CLAIMED", "没有可标记失联的CLAIMED启动");
        }
        return launchView(mapper.lockLaunch(enterpriseId, attemptId, epoch), attempt);
    }

    /** 记录已知空 XID，不写入 attempt.xid，供受控回滚清理。 */
    public Map<String, Object> recordKnownEmptyXid(String enterpriseId, String attemptId, String xid) {
        xid = requireXid(xid);
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        if (nullable(attempt.get("xid")) != null) {
            throw new FulfillmentException("XID_ALREADY_BOUND", "已绑定XID不能记为空启动");
        }
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        if (mapper.recordEmptyXid(enterpriseId, attemptId, epoch, xid, LAUNCH_UNKNOWN, CLEANUP_PENDING, now()) != 1) {
            throw new FulfillmentException("LAUNCH_NOT_CLAIMED", "没有可记录空XID的启动行");
        }
        return launchView(mapper.lockLaunch(enterpriseId, attemptId, epoch), attempt);
    }

    /**
     * 仅当启动已标 UNKNOWN、attempt 未绑定且无仓级 reservation 时提升代际。
     * 失败者不得 Try；旧执行器随后 bind 会因代际失败。
     */
    public Map<String, Object> isolateEmptyLaunch(String enterpriseId, String attemptId, String recovererId) {
        requireId(recovererId, "INVALID_EXECUTOR", "恢复执行器不能为空");
        Timestamp now = now();
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        if (nullable(attempt.get("xid")) != null) {
            throw new FulfillmentException("XID_ALREADY_BOUND", "已绑定XID不能隔离空启动");
        }
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        if (mapper.isolateEmptyLaunch(enterpriseId, attemptId, recovererId,
                Timestamp.from(clock.instant().plus(LAUNCH_LEASE)), epoch,
                ((Number) attempt.get("version")).longValue(), ATTEMPT_STARTING, now) != 1) {
            throw new FulfillmentException("LAUNCH_NOT_ISOLATED", "空启动未证明可隔离，保持RECOVERY_PENDING");
        }
        mapper.insertLaunch(UUID.randomUUID().toString(), enterpriseId, attemptId, epoch + 1, recovererId,
                LAUNCH_CLAIMED, CLEANUP_NONE, now);
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 兼容未知启动的等待标记；知道XID不等于已清理，缺证据时必须拒绝CLEANED。 */
    public Map<String, Object> cleanupEmptyLaunch(String enterpriseId, String attemptId) {
        return cleanupEmptyLaunch(enterpriseId, attemptId, null, null);
    }

    /** 只有可信端口读取到原环境/原XID的回滚终态才允许清理，原证据与状态同事务保存。 */
    public Map<String, Object> cleanupEmptyLaunch(String enterpriseId, String attemptId,
            TcStatusPort.Observation observation, TcEvidenceScope scope) {
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        if (nullable(attempt.get("xid")) != null) {
            throw new FulfillmentException("XID_ALREADY_BOUND", "已绑定XID不能当空启动清理");
        }
        long epoch = ((Number) attempt.get("launch_epoch")).longValue();
        Map<String, Object> launch = mapper.lockLaunch(enterpriseId, attemptId, epoch);
        if (launch == null) {
            throw new FulfillmentException("LAUNCH_NOT_CLAIMED", "启动记录不存在");
        }
        String xid = nullable(launch.get("xid"));
        String cleanup = xid == null ? CLEANUP_WAITING_TIMEOUT : CLEANUP_CLEANED;
        String evidence = null;
        if (xid != null) {
            if (observation == null || scope == null) throw new FulfillmentException("TC_EVIDENCE_REQUIRED", "已知空XID仍需原TC回滚终态证据");
            try {
                var proof = JSON.readTree(observation.evidence());
                int code = "Rollbacked".equals(observation.status()) ? 11 : "TimeoutRollbacked".equals(observation.status()) ? 13 : -1;
                if (code < 0 || !proof.path("status").isIntegralNumber() || !proof.path("status").canConvertToInt()
                        || proof.path("status").asInt() != code || !xid.equals(proof.path("xid").asString())
                        || !scope.clusterId().equals(proof.path("clusterId").asString())
                        || !scope.applicationId().equals(proof.path("applicationId").asString())
                        || !scope.transactionGroup().equals(proof.path("transactionGroup").asString())) throw new IllegalArgumentException();
                evidence = proof.toString();
            } catch (RuntimeException invalid) { throw new FulfillmentException("INVALID_TC_EVIDENCE", "清理证据必须属于原空XID及TC环境的回滚终态"); }
        }
        if (cleanup.equals(launch.get("cleanup_state"))) {
            if (!sameJson(evidence, nullable(launch.get("cleanup_terminal_evidence"))))
                throw new FulfillmentException("TC_EVIDENCE_CONFLICT", "原清理证据不可覆盖或补造");
            return launchView(launch, attempt);
        }
        if (mapper.cleanupLaunch(enterpriseId, attemptId, epoch, cleanup,
                xid == null ? "UNKNOWN_EMPTY_XID" : "KNOWN_EMPTY_XID", evidence, now()) != 1) {
            throw new FulfillmentException("LAUNCH_CLEANUP_CONFLICT", "空启动清理竞争");
        }
        return launchView(mapper.lockLaunch(enterpriseId, attemptId, epoch), attempt);
    }

    private static boolean sameJson(String left, String right) {
        return left == null ? right == null : right != null && JSON.readTree(left).equals(JSON.readTree(right));
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
        // TC终态只能追加一次；乱序非终态和互相矛盾的终态都不能抹掉已有证据。
        if (evidence != null && !evidence.isBlank()) {
            try {
                var json = JSON.readTree(evidence);
                int expected = switch (observedStatus) {
                    case "Committed" -> 9;
                    case "Rollbacked" -> 11;
                    case "TimeoutRollbacked" -> 13;
                    default -> throw new IllegalArgumentException();
                };
                if (!json.isObject() || !json.path("xid").isString()
                        || !java.util.Objects.equals(nullable(attempt.get("xid")), json.path("xid").asString())
                        || !json.path("status").isIntegralNumber() || json.path("status").asInt() != expected) {
                    throw new IllegalArgumentException();
                }
                Object previous = attempt.get("tc_terminal_evidence");
                if (previous != null && !String.valueOf(previous).isBlank()) {
                    if (!observedStatus.equals(attempt.get("tc_observed_status"))
                            || !json.equals(JSON.readTree(String.valueOf(previous)))) {
                        throw new FulfillmentException("TC_EVIDENCE_CONFLICT", "已记录终态证据不可覆盖");
                    }
                    return attemptView(attempt, mapper.lockParticipants(enterpriseId, attemptId));
                }
            } catch (FulfillmentException conflict) { throw conflict;
            } catch (RuntimeException invalid) {
                throw new FulfillmentException("INVALID_TC_EVIDENCE", "证据必须属于绑定XID和明确TC终态");
            }
        } else if (attempt.get("tc_terminal_evidence") != null) {
            throw new FulfillmentException("TC_EVIDENCE_CONFLICT", "非终态观察不得覆盖已记录终态");
        }
        if (mapper.observeTc(enterpriseId, attemptId, observedStatus, evidence, now) != 1) {
            throw new FulfillmentException("VERSION_CONFLICT", "TC观察写入失败");
        }
        return attemptView(mapper.lockAttempt(enterpriseId, attemptId), mapper.lockParticipants(enterpriseId, attemptId));
    }

    /** 绑定仓级XID/branch/action一次。fulfillment只观察，不驱动Confirm/Cancel。 */
    public Map<String, Object> bindParticipant(String enterpriseId, String attemptId, String warehouseId,
            String xid, long branchId, String actionName, String reservationId, long routeEpoch, String branchState) {
        requireId(warehouseId, "INVALID_PARTICIPANT", "仓库不能为空");
        requireId(actionName, "INVALID_PARTICIPANT", "TCC动作名不能为空");
        requireId(reservationId, "INVALID_PARTICIPANT", "预占标识不能为空");
        requireId(branchState, "INVALID_PARTICIPANT", "分支观察状态不能为空");
        xid = requireXid(xid);
        if (branchId <= 0 || routeEpoch < 0) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "branchId必须为正且routeEpoch不能为负");
        }
        FulfillmentMapper mapper = session.getMapper(FulfillmentMapper.class);
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
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
                if (participant.get("xid") != null) {
                    if (!xid.equals(participant.get("xid")) || !actionName.equals(participant.get("action_name"))
                            || !reservationId.equals(participant.get("reservation_id"))
                            || !(participant.get("branch_id") instanceof Number originalBranch) || originalBranch.longValue() != branchId
                            || !(participant.get("route_epoch") instanceof Number originalRoute) || originalRoute.longValue() != routeEpoch) {
                        throw new FulfillmentException("BRANCH_ALREADY_BOUND", "仓级分支身份不可改绑");
                    }
                    // 原Try回执可能晚于Confirm或截止时刻；同身份只读取，不回退观察状态或重做Try。
                    return attemptView(attempt, participants);
                }
                break;
            }
        }
        if (!found) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "参与仓不存在");
        }
        refuseExpiredTry(attempt);
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
        Map<String, Object> attempt = requireAttempt(mapper, enterpriseId, attemptId);
        Map<String, Object> participant = mapper.lockParticipants(enterpriseId, attemptId).stream()
                .filter(row -> warehouseId.equals(row.get("warehouse_id"))).findFirst()
                .orElseThrow(() -> new FulfillmentException("INVALID_PARTICIPANT", "参与仓不存在"));
        if (!Set.of("TRIED", "CONFIRMED", "CANCELLED").contains(state)) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "未知分支观察状态");
        }
        if (PARTICIPANT_CONFIRMED.equals(state) && (!participantBound(attempt, participant)
                || confirmedVersion == null || confirmedVersion < 1)) {
            throw new FulfillmentException("PARTICIPANT_EVIDENCE_MISSING", "确认必须绑定原始分支身份和确认版本");
        }
        if (Set.of("CONFIRMED", "CANCELLED").contains(String.valueOf(participant.get("state")))) {
            if (!state.equals(participant.get("state"))
                    || !java.util.Objects.equals(confirmedVersion, participant.get("confirmed_version"))) {
                throw new FulfillmentException("PARTICIPANT_EVIDENCE_CONFLICT", "分支终态不可回退或改写");
            }
            return attemptView(attempt, mapper.lockParticipants(enterpriseId, attemptId));
        }
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
        String observed = nullable(attempt.get("tc_observed_status"));
        String evidence = nullable(attempt.get("tc_terminal_evidence"));
        if (!TC_COMMITTED.equals(observed) || evidence == null || evidence.isBlank()) {
            throw new FulfillmentException("ALLOCATED_EVIDENCE_MISSING", "缺少TC终态证据，拒绝ALLOCATED");
        }
        if (participants.isEmpty()) {
            throw new FulfillmentException("INVALID_PARTICIPANT", "attempt没有固定参与者");
        }
        for (Map<String, Object> participant : participants) {
            if (!PARTICIPANT_CONFIRMED.equals(String.valueOf(participant.get("state")))
                    || !participantBound(attempt, participant)
                    || !(participant.get("confirmed_version") instanceof Number confirmed) || confirmed.longValue() < 1) {
                throw new FulfillmentException("PARTICIPANTS_NOT_CONFIRMED", "固定参与者尚未全部CONFIRMED");
            }
        }
        Map<String, Object> order = mapper.lockOrder(enterpriseId, String.valueOf(attempt.get("fulfillment_id")));
        if (order == null || !attemptId.equals(order.get("active_attempt_id"))) {
            throw new FulfillmentException("ATTEMPT_SUPERSEDED", "旧attempt不能派发执行授权");
        }
        // 已签发的屏障重放不能因后来的取消请求回退；撤销执行能力需走独立补偿协议。
        if (order.get("owner_id") != null && cancelRequested(attempt.get("cancel_requested"))
                && !ATTEMPT_ALLOCATED.equals(String.valueOf(attempt.get("state"))))
            throw new FulfillmentException("CANCEL_REQUIRES_COMPENSATION", "取消已请求，须先处理原事务结果和库存补偿");
        if (ATTEMPT_ALLOCATED.equals(String.valueOf(attempt.get("state")))) {
            writeBarrierOutbox(mapper, enterpriseId, attemptId, attempt, participants);
            return attemptView(attempt, participants);
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
            return com.lrj.wms.runtime.db.DatabaseInstants.require(local);
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

    /** 状态文本不能代替持久化的XID、分支、资源、预占和路由身份。 */
    private static boolean participantBound(Map<String, Object> attempt, Map<String, Object> participant) {
        return nullable(attempt.get("xid")) != null
                && attempt.get("xid").equals(participant.get("xid"))
                && participant.get("branch_id") instanceof Number branch && branch.longValue() > 0
                && nullable(participant.get("action_name")) != null
                && nullable(participant.get("reservation_id")) != null;
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
                        + escape(warehouses) + "\"}", null, now);
        List<Map<String, Object>> lines = mapper.listParticipantLines(enterpriseId, attemptId);
        var delivery = AllocationAuthorizationFactory.snapshots(session, enterpriseId,
                mapper.lockOrder(enterpriseId, String.valueOf(attempt.get("fulfillment_id"))), attempt, participants, lines);
        for (Map<String, Object> participant : participants) {
            String warehouseId = String.valueOf(participant.get("warehouse_id"));
            String reservationId = nullable(participant.get("reservation_id"));
            String payload = "{\"attemptId\":\"" + escape(attemptId) + "\",\"xid\":\"" + escape(xid)
                    + "\",\"warehouseId\":\"" + escape(warehouseId) + "\",\"reservationId\":\""
                    + escape(reservationId) + "\",\"lines\":" + linesJson(lines, warehouseId) + "}";
            insertOutbox(mapper, enterpriseId, attemptId, warehouseId, EVENT_OUTBOUND_ORDER_REQUESTED, payload, delivery.get(warehouseId), now);
            insertOutbox(mapper, enterpriseId, attemptId, warehouseId, EVENT_EXECUTION_AUTHORIZATION_REQUESTED,
                    payload, delivery.get(warehouseId), now);
        }
    }

    private static void insertOutbox(FulfillmentMapper mapper, String enterpriseId, String attemptId,
            String warehouseId, String eventType, String payload, String delivery, Timestamp now) {
        String eventId = UUID.randomUUID().toString();
        mapper.insertOutboxIgnore(eventId, enterpriseId, attemptId, warehouseId, eventType,
                operationId(eventType, attemptId, warehouseId), payload, now);
        var existing = mapper.getBarrierOutbox(enterpriseId, attemptId, warehouseId, eventType);
        var json = JSON;
        if (existing == null || !operationId(eventType, attemptId, warehouseId).equals(existing.get("operation_id"))
                || !json.readTree(payload).equals(json.readTree(String.valueOf(existing.get("payload"))))) {
            throw new FulfillmentException("BARRIER_OUTBOX_CONFLICT", "屏障事件原身份和正文不一致");
        }
        if (delivery != null) {
            if (existing.get("delivery_payload") == null) {
                if (!eventId.equals(existing.get("event_id")) || mapper.bindOutboxDelivery(eventId, delivery) != 1)
                    throw new FulfillmentException("LEGACY_AUTHORIZATION_CONTEXT_MISSING", "旧事件缺完整授权快照，不自动补齐");
            } else if (!com.lrj.wms.runtime.messaging.RuntimeMessage.contentHash(delivery).equals(
                    com.lrj.wms.runtime.messaging.RuntimeMessage.contentHash(String.valueOf(existing.get("delivery_payload"))))) {
                throw new FulfillmentException("BARRIER_OUTBOX_CONFLICT", "屏障投递快照与原始事实不一致");
            }
        }
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

    /** 由JSON库处理引号、反斜杠和控制字符，防合法来源标识使授权事件无法持久化。 */
    private static String escape(Object value) {
        String quoted = JSON.writeValueAsString(value == null ? "" : String.valueOf(value));
        return quoted.substring(1, quoted.length() - 1);
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
        body.put("ownerId", order.get("owner_id"));
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
        body.put("deadline", attempt.get("deadline"));
        body.put("launchEpoch", attempt.get("launch_epoch"));
        body.put("launchOwner", attempt.get("launch_owner"));
        body.put("cancelRequested", cancelRequested(attempt.get("cancel_requested")));
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

    private static Map<String, Object> launchView(Map<String, Object> launch, Map<String, Object> attempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", attempt.get("id"));
        body.put("attemptState", attempt.get("state"));
        body.put("attemptXid", attempt.get("xid"));
        body.put("launchEpoch", launch.get("launch_epoch"));
        body.put("executorId", launch.get("executor_id"));
        body.put("launchXid", launch.get("xid"));
        body.put("launchState", launch.get("state"));
        body.put("cleanupState", launch.get("cleanup_state"));
        body.put("errorCode", launch.get("error_code"));
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

    private static Map<String, Object> cancelView(Map<String, Object> cancellation, Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancellationId", cancellation.get("id"));
        body.put("fulfillmentId", cancellation.get("fulfillment_id"));
        body.put("attemptId", cancellation.get("attempt_id"));
        body.put("status", cancellation.get("state"));
        body.put("operationId", cancellation.get("id"));
        if (order != null) {
            body.put("orderStatus", order.get("status"));
            body.put("activeAttemptId", order.get("active_attempt_id"));
        }
        return body;
    }

    private static boolean cancelRequested(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String nullable(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
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
