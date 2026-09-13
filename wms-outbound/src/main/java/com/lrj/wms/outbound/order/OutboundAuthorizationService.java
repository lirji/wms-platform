package com.lrj.wms.outbound.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/**
 * 核验本库 TCC Committed 证据后绑定出库执行授权。
 * 不调用履约 markAllocated，不发明跨仓 ALLOCATED。
 */
public final class OutboundAuthorizationService {
    public static final String TC_COMMITTED = "Committed";
    public static final String AUTHORIZED = "AUTHORIZED";

    private final SqlSession session;
    private final Clock clock;

    public OutboundAuthorizationService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Map<String, Object> authorize(String enterpriseId, String warehouseId, String orderId, String clientOperationId,
            String actorId, String attemptId, String authorizationId, String xid, String evidenceRef,
            String participantSetHash) {
        require(clientOperationId, "INVALID_ARGUMENT", "命令键不能为空");
        require(attemptId, "INVALID_ARGUMENT", "attemptId不能为空");
        require(authorizationId, "AUTH_REQUIRED", "authorizationId不能为空");
        require(xid, "TCC_NOT_COMMITTED", "缺少XID，拒绝授权");
        require(evidenceRef, "TCC_NOT_COMMITTED", "缺少TC终态证据引用，拒绝授权");
        require(participantSetHash, "TCC_NOT_COMMITTED", "缺少参与者摘要，拒绝授权");
        OutboundAuthorizationMapper auths = session.getMapper(OutboundAuthorizationMapper.class);
        // 先锁单据再读幂等记录，避免同单并发授权使用不同快照并回退业务状态。
        Map<String, Object> order = session.getMapper(OutboundOrderMapper.class).lockOrder(enterpriseId, warehouseId, orderId);
        if (order == null) throw new OutboundException("UNKNOWN_ORDER", "出库单不存在");
        if (session.getMapper(OutboundOrderMapper.class).cancellation(enterpriseId,warehouseId,orderId)!=null)
            throw new OutboundException("AUTH_CONFLICT","原取消门禁禁止迟到授权");
        Map<String, Object> existing = auths.getAuthorizationByKey(enterpriseId, warehouseId, clientOperationId);
        if (existing == null) existing = auths.getAuthorizationById(enterpriseId, warehouseId, authorizationId);
        if (existing != null) {
            if (!orderId.equals(String.valueOf(existing.get("outbound_order_id")))
                    || !authorizationId.equals(String.valueOf(existing.get("authorization_id")))
                    || !attemptId.equals(String.valueOf(existing.get("attempt_id")))
                    || !xid.equals(String.valueOf(existing.get("xid")))
                    || !evidenceRef.equals(String.valueOf(existing.get("tc_terminal_evidence_ref")))
                    || !participantSetHash.equals(String.valueOf(existing.get("participant_set_hash")))) {
                throw new OutboundException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键授权内容不一致");
            }
            if (!authorizationId.equals(order.get("execution_authorization_id"))
                    || auths.verifiedAuthorization(enterpriseId, warehouseId, orderId, attemptId, authorizationId) == null) {
                throw new OutboundException("AUTH_CONFLICT", "既有授权已失效或不再绑定当前单据");
            }
            return view(existing, order);
        }
        if (!OutboundOrderService.STATUS_PENDING_AUTHORIZATION.equals(order.get("status"))) {
            throw new OutboundException("AUTH_CONFLICT", "当前业务状态不接受新的执行授权");
        }
        if (!attemptId.equals(String.valueOf(order.get("attempt_id")))) {
            throw new OutboundException("ATTEMPT_MISMATCH", "attempt与出库单不一致");
        }
        Object currentAuth = order.get("execution_authorization_id");
        if (currentAuth != null && !String.valueOf(currentAuth).isBlank()
                && !authorizationId.equals(String.valueOf(currentAuth))) {
            throw new OutboundException("AUTH_CONFLICT", "出库单已绑定其他执行授权");
        }
        Map<String, Object> evidence = auths.getEvidence(enterpriseId, warehouseId, attemptId);
        if (evidence == null || !TC_COMMITTED.equals(String.valueOf(evidence.get("tc_observed_status")))
                || blank(evidence.get("tc_terminal_evidence_ref"))) {
            throw new OutboundException("TCC_NOT_COMMITTED", "缺少本库TC Committed证据，拒绝授权");
        }
        if (!xid.equals(String.valueOf(evidence.get("xid")))
                || !evidenceRef.equals(String.valueOf(evidence.get("tc_terminal_evidence_ref")))
                || !participantSetHash.equals(String.valueOf(evidence.get("participant_set_hash")))) {
            throw new OutboundException("EVIDENCE_MISMATCH", "请求与已落库TCC证据不一致");
        }
        Timestamp now = Timestamp.from(clock.instant());
        if (auths.insertAuthorizationIgnore(UUID.randomUUID().toString(), enterpriseId, warehouseId, orderId,
                clientOperationId, authorizationId, attemptId, xid, evidenceRef, participantSetHash, actorId,
                AUTHORIZED, now) != 1) {
            throw new OutboundException("AUTH_CONFLICT", "执行授权身份已被占用");
        }
        if (auths.casBindAuthorization(enterpriseId, warehouseId, orderId, authorizationId, now) != 1) {
            throw new OutboundException("VERSION_CONFLICT", "执行授权绑定冲突");
        }
        return view(auths.getAuthorizationByKey(enterpriseId, warehouseId, clientOperationId),
                session.getMapper(OutboundOrderMapper.class).lockOrder(enterpriseId, warehouseId, orderId));
    }

    /** 人工作业与设备派工共用成功屏障；持有本地事务锁直到用例提交。 */
    public void requireExecutable(String enterpriseId, String warehouseId, Map<String, Object> order) {
        if(order!=null && session.getMapper(OutboundOrderMapper.class).cancellation(enterpriseId,warehouseId,String.valueOf(order.get("id")))!=null)
            throw new OutboundException("CANCELLATION_IN_PROGRESS","原取消已阻断新作业，原回执仍可恢复");
        Object authorization = order == null ? null : order.get("execution_authorization_id");
        if (authorization == null || String.valueOf(authorization).isBlank()
                || session.getMapper(OutboundAuthorizationMapper.class).verifiedAuthorization(enterpriseId, warehouseId,
                        String.valueOf(order.get("id")), String.valueOf(order.get("attempt_id")), String.valueOf(authorization)) == null) {
            throw new OutboundException("AUTH_REQUIRED", "作业必须有与当前单据和 Committed 证据一致的执行授权");
        }
    }

    private static Map<String, Object> view(Map<String, Object> authorization, Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", authorization.get("id"));
        body.put("authorizationId", authorization.get("authorization_id"));
        body.put("outboundOrderId", authorization.get("outbound_order_id"));
        body.put("attemptId", authorization.get("attempt_id"));
        body.put("state", authorization.get("state"));
        body.put("status", authorization.get("state"));
        if (order != null) {
            body.put("orderStatus", order.get("status"));
            body.put("executionAuthorizationId", order.get("execution_authorization_id"));
        }
        return body;
    }

    private static void require(String value, String code, String message) {
        if (blank(value)) {
            throw new OutboundException(code, message);
        }
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
