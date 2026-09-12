package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 调拨总单与在途累计。源发出/目的接收按仓+动作+operationId 去重。
 * 不持库存事务，不发明 OQ-03。额度 token 留给 S6-01a。
 */
public final class TransferService {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    public static final String STATUS_RECEIVING = "RECEIVING";
    public static final String ACTION_ISSUE = "ISSUE";
    public static final String ACTION_RECEIVE = "RECEIVE";
    public static final String ACTION_LOSS = "LOSS";
    public static final String ROLE_SOURCE = "SOURCE";
    public static final String ROLE_TARGET = "TARGET";
    public static final String AUTH_OPEN = "OPEN";
    public static final String AUTH_CONSUMED = "CONSUMED";
    public static final String AUTH_CANCELLED = "CANCELLED";
    public static final String NO_LOT = "NO_LOT";

    private final SqlSession session;
    private final Clock clock;

    public TransferService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 创建总单、源/目的子单与在途行。同 transferId 重放返回原单。 */
    public Map<String, Object> create(String enterpriseId, String transferId, String sourceWarehouseId,
            String targetWarehouseId, List<Map<String, Object>> lines) {
        require(enterpriseId, "INVALID_ENTERPRISE", "企业不能为空");
        require(transferId, "INVALID_TRANSFER", "调拨单不能为空");
        require(sourceWarehouseId, "INVALID_WAREHOUSE", "源仓不能为空");
        require(targetWarehouseId, "INVALID_WAREHOUSE", "目的仓不能为空");
        if (sourceWarehouseId.equals(targetWarehouseId)) {
            throw new TransferException("INVALID_WAREHOUSE", "源仓与目的仓不能相同");
        }
        if (lines == null || lines.isEmpty()) {
            throw new TransferException("INVALID_LINE", "调拨行不能为空");
        }
        Timestamp now = now();
        TransferMapper mapper = mapper();
        mapper.insertOrderIgnore(transferId, enterpriseId, sourceWarehouseId, targetWarehouseId, STATUS_OPEN, now);
        Map<String, Object> order = mapper.lockOrder(enterpriseId, transferId);
        if (order == null) {
            throw new TransferException("VERSION_CONFLICT", "调拨单创建竞争");
        }
        if (!sourceWarehouseId.equals(String.valueOf(order.get("source_warehouse_id")))
                || !targetWarehouseId.equals(String.valueOf(order.get("target_warehouse_id")))) {
            throw new TransferException("TRANSFER_CONFLICT", "同单调拨仓不一致");
        }
        if (mapper.listLines(enterpriseId, transferId).isEmpty()) {
            mapper.insertLeg(UUID.randomUUID().toString(), enterpriseId, transferId, sourceWarehouseId, ROLE_SOURCE,
                    STATUS_OPEN, now);
            mapper.insertLeg(UUID.randomUUID().toString(), enterpriseId, transferId, targetWarehouseId, ROLE_TARGET,
                    STATUS_OPEN, now);
            for (Map<String, Object> line : lines) {
                mapper.insertLine(required(line, "lineId"), enterpriseId, transferId, required(line, "skuId"),
                        String.valueOf(line.getOrDefault("businessLotKey", NO_LOT)),
                        String.valueOf(line.getOrDefault("sourceLotId", NO_LOT)), requiredQty(line.get("plannedQty")),
                        now);
            }
        }
        return view(enterpriseId, transferId);
    }

    /** 源仓发出。同操作键重放原事实，不二次加 issued。 */
    public Map<String, Object> issue(String enterpriseId, String transferId, String lineId, String operationId,
            BigDecimal qty) {
        return applyFact(enterpriseId, transferId, lineId, operationId, qty, ACTION_ISSUE);
    }

    /** 申请目的接收额度。同 targetClientOperationId 重放原 token，不二次占额度。 */
    public Map<String, Object> authorizeReceipt(String enterpriseId, String transferId, String lineId,
            String targetClientOperationId, BigDecimal qty) {
        require(targetClientOperationId, "INVALID_OPERATION", "目的客户端操作键不能为空");
        requireQty(qty);
        Timestamp now = now();
        TransferMapper mapper = mapper();
        Map<String, Object> order = requireOrder(mapper, enterpriseId, transferId);
        requireLine(mapper, enterpriseId, transferId, lineId);
        String warehouseId = String.valueOf(order.get("target_warehouse_id"));
        Map<String, Object> existing = mapper.lockAuthByClient(enterpriseId, lineId, targetClientOperationId);
        if (existing != null) {
            if (qty.compareTo(decimal(existing.get("quantity"))) != 0) {
                throw new TransferException("OPERATION_CONFLICT", "同客户端操作额度不一致");
            }
            return authView(existing, true);
        }
        if (mapper.addQuota(enterpriseId, transferId, lineId, qty, now) != 1) {
            throw new TransferException("OVER_QUOTA", "接收额度超过在途可收量");
        }
        String authId = UUID.randomUUID().toString();
        if (mapper.insertAuthIgnore(authId, enterpriseId, transferId, lineId, warehouseId, targetClientOperationId, qty,
                now) != 1) {
            throw new TransferException("VERSION_CONFLICT", "额度授权竞争");
        }
        return authView(mapper.lockAuth(enterpriseId, authId), false);
    }

    /** 目的仓凭 token 接收。同操作键重放原事实；消费与行锁同事务。 */
    public Map<String, Object> receive(String enterpriseId, String transferId, String lineId, String operationId,
            String authorizationId, long tokenVersion, BigDecimal qty, String targetLotId) {
        require(authorizationId, "INVALID_AUTHORIZATION", "接收授权不能为空");
        TransferMapper mapper = mapper();
        requireOrder(mapper, enterpriseId, transferId);
        requireLine(mapper, enterpriseId, transferId, lineId);
        Map<String, Object> auth = mapper.lockAuth(enterpriseId, authorizationId);
        if (auth == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "接收授权不存在");
        }
        if (!lineId.equals(String.valueOf(auth.get("transfer_line_id")))
                || qty.compareTo(decimal(auth.get("quantity"))) != 0
                || tokenVersion != ((Number) auth.get("token_version")).longValue()) {
            throw new TransferException("TOKEN_MISMATCH", "授权数量或版本不匹配");
        }
        if (AUTH_CANCELLED.equals(String.valueOf(auth.get("state")))) {
            throw new TransferException("TOKEN_CANCELLED", "授权已取消");
        }
        Map<String, Object> fact = applyFact(enterpriseId, transferId, lineId, operationId, qty, ACTION_RECEIVE);
        if (Boolean.TRUE.equals(fact.get("replayed")) || AUTH_CONSUMED.equals(String.valueOf(auth.get("state")))) {
            return fact;
        }
        Timestamp now = now();
        if (mapper.consumeQuota(enterpriseId, transferId, lineId, qty, now) != 1) {
            throw new TransferException("OVER_RECEIVE", "接收超过授权额度");
        }
        if (mapper.casAuthState(enterpriseId, authorizationId, AUTH_OPEN, AUTH_CONSUMED, tokenVersion, operationId,
                now) != 1) {
            throw new TransferException("VERSION_CONFLICT", "授权消费竞争");
        }
        if (targetLotId != null && !targetLotId.isBlank()
                && mapper.bindTargetLot(enterpriseId, transferId, lineId, targetLotId, now) != 1) {
            throw new TransferException("LOT_MAPPING_CONFLICT", "目的批次映射不一致");
        }
        return fact;
    }

    /** 取消未消费 token，释放额度。已消费返回原接收事实，不回收。 */
    public Map<String, Object> cancelAuthorization(String enterpriseId, String transferId, String authorizationId) {
        Timestamp now = now();
        TransferMapper mapper = mapper();
        requireOrder(mapper, enterpriseId, transferId);
        Map<String, Object> auth = mapper.lockAuth(enterpriseId, authorizationId);
        if (auth == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "接收授权不存在");
        }
        if (AUTH_CONSUMED.equals(String.valueOf(auth.get("state")))) {
            return authView(auth, true);
        }
        if (AUTH_CANCELLED.equals(String.valueOf(auth.get("state")))) {
            return authView(auth, true);
        }
        BigDecimal qty = decimal(auth.get("quantity"));
        String lineId = String.valueOf(auth.get("transfer_line_id"));
        if (mapper.releaseQuota(enterpriseId, transferId, lineId, qty, now) != 1) {
            throw new TransferException("VERSION_CONFLICT", "额度释放竞争");
        }
        if (mapper.casAuthState(enterpriseId, authorizationId, AUTH_OPEN, AUTH_CANCELLED,
                ((Number) auth.get("token_version")).longValue(), null, now) != 1) {
            throw new TransferException("VERSION_CONFLICT", "授权取消竞争");
        }
        return authView(mapper.lockAuth(enterpriseId, authorizationId), false);
    }

    /** 确认损耗，与接收额度竞争同一行锁。 */
    public Map<String, Object> confirmLoss(String enterpriseId, String transferId, String lineId, String operationId,
            BigDecimal qty) {
        TransferMapper mapper = mapper();
        requireOrder(mapper, enterpriseId, transferId);
        Map<String, Object> line = requireLine(mapper, enterpriseId, transferId, lineId);
        if (decimal(line.get("received_qty")).add(decimal(line.get("loss_confirmed_qty")))
                .add(decimal(line.get("active_receipt_quota"))).add(qty)
                .compareTo(decimal(line.get("issued_qty"))) > 0) {
            throw new TransferException("OVER_LOSS", "损耗超过在途可定量");
        }
        Map<String, Object> fact = applyFact(enterpriseId, transferId, lineId, operationId, qty, ACTION_LOSS);
        if (Boolean.TRUE.equals(fact.get("replayed"))) {
            return fact;
        }
        if (mapper.addLoss(enterpriseId, transferId, lineId, qty, now()) != 1) {
            throw new TransferException("OVER_LOSS", "损耗超过在途可定量");
        }
        return fact;
    }

    public Map<String, Object> get(String enterpriseId, String transferId) {
        if (mapper().lockOrder(enterpriseId, transferId) == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "调拨单不存在");
        }
        return view(enterpriseId, transferId);
    }

    public List<Map<String, Object>> list(String enterpriseId, int limit) {
        return mapper().listOrders(enterpriseId, limit);
    }

    private Map<String, Object> applyFact(String enterpriseId, String transferId, String lineId, String operationId,
            BigDecimal qty, String action) {
        require(operationId, "INVALID_OPERATION", "操作键不能为空");
        requireQty(qty);
        Timestamp now = now();
        TransferMapper mapper = mapper();
        Map<String, Object> order = mapper.lockOrder(enterpriseId, transferId);
        if (order == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "调拨单不存在");
        }
        Map<String, Object> line = mapper.lockLine(enterpriseId, transferId, lineId);
        if (line == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "调拨行不存在");
        }
        String warehouseId = ACTION_ISSUE.equals(action) ? String.valueOf(order.get("source_warehouse_id"))
                : String.valueOf(order.get("target_warehouse_id"));
        Map<String, Object> existing = mapper.getFact(enterpriseId, warehouseId, action, operationId);
        if (existing != null) {
            if (!lineId.equals(String.valueOf(existing.get("line_id")))
                    || qty.compareTo(decimal(existing.get("quantity"))) != 0) {
                throw new TransferException("OPERATION_CONFLICT", "同操作键内容不一致");
            }
            return factView(existing, true);
        }
        if (ACTION_ISSUE.equals(action)
                && decimal(line.get("issued_qty")).add(qty).compareTo(decimal(line.get("planned_qty"))) > 0) {
            throw new TransferException("OVER_ISSUE", "发出超过计划数量");
        }
        int inserted = mapper.insertFactIgnore(UUID.randomUUID().toString(), enterpriseId, transferId, lineId,
                warehouseId, action, operationId, qty, now);
        if (inserted != 1) {
            Map<String, Object> raced = mapper.getFact(enterpriseId, warehouseId, action, operationId);
            if (raced == null) {
                throw new TransferException("VERSION_CONFLICT", "调拨事实竞争");
            }
            if (!lineId.equals(String.valueOf(raced.get("line_id")))
                    || qty.compareTo(decimal(raced.get("quantity"))) != 0) {
                throw new TransferException("OPERATION_CONFLICT", "同操作键内容不一致");
            }
            return factView(raced, true);
        }
        if (ACTION_ISSUE.equals(action)) {
            if (mapper.addIssued(enterpriseId, transferId, lineId, qty, now) != 1) {
                throw new TransferException("OVER_ISSUE", "发出超过计划数量");
            }
            mapper.updateOrderStatus(enterpriseId, transferId, STATUS_IN_TRANSIT, now);
            mapper.updateLegStatus(enterpriseId, transferId, warehouseId, STATUS_IN_TRANSIT, now);
        } else {
            mapper.updateOrderStatus(enterpriseId, transferId, STATUS_RECEIVING, now);
            mapper.updateLegStatus(enterpriseId, transferId, warehouseId, STATUS_RECEIVING, now);
        }
        Map<String, Object> stored = mapper.getFact(enterpriseId, warehouseId, action, operationId);
        return factView(stored, false);
    }

    private Map<String, Object> view(String enterpriseId, String transferId) {
        TransferMapper mapper = mapper();
        Map<String, Object> order = mapper.lockOrder(enterpriseId, transferId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", order.get("id"));
        body.put("status", order.get("status"));
        body.put("sourceWarehouseId", order.get("source_warehouse_id"));
        body.put("targetWarehouseId", order.get("target_warehouse_id"));
        body.put("legs", mapper.listLegs(enterpriseId, transferId));
        body.put("lines", mapper.listLines(enterpriseId, transferId));
        return body;
    }

    private static Map<String, Object> factView(Map<String, Object> fact, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", fact.get("transfer_id"));
        body.put("lineId", fact.get("line_id"));
        body.put("operationId", fact.get("operation_id"));
        body.put("action", fact.get("action"));
        body.put("quantity", fact.get("quantity"));
        body.put("replayed", replayed);
        return body;
    }

    private Map<String, Object> requireOrder(TransferMapper mapper, String enterpriseId, String transferId) {
        Map<String, Object> order = mapper.lockOrder(enterpriseId, transferId);
        if (order == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "调拨单不存在");
        }
        return order;
    }

    private Map<String, Object> requireLine(TransferMapper mapper, String enterpriseId, String transferId,
            String lineId) {
        Map<String, Object> line = mapper.lockLine(enterpriseId, transferId, lineId);
        if (line == null) {
            throw new TransferException("RESOURCE_NOT_FOUND", "调拨行不存在");
        }
        return line;
    }

    private static Map<String, Object> authView(Map<String, Object> auth, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorizationId", auth.get("id"));
        body.put("tokenVersion", auth.get("token_version"));
        body.put("quantity", auth.get("quantity"));
        body.put("state", auth.get("state"));
        body.put("replayed", replayed);
        return body;
    }

    private TransferMapper mapper() {
        return session.getMapper(TransferMapper.class);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static void require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new TransferException(code, message);
        }
    }

    private static String required(Map<String, Object> line, String key) {
        Object value = line.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new TransferException("INVALID_LINE", "缺少" + key);
        }
        return String.valueOf(value);
    }

    private static void requireQty(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new TransferException("INVALID_QTY", "数量必须为正");
        }
    }

    private static BigDecimal requiredQty(Object value) {
        BigDecimal qty = decimal(value);
        requireQty(qty);
        return qty;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
