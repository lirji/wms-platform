package com.lrj.wms.probe;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 只读查询TC终态证据，并结合attempt/XID/代际/参与者Fence决定是否放行。
 * 生产期望TM应用身份为{@code wms-fulfillment}；本探针TM仍是{@code wms-s0-db-probe}。
 */
final class TerminalEvidenceAdapter {
    enum Decision {
        ALLOW_ALLOCATED,
        RECOVERY_PENDING,
        DENIED
    }

    static final String PRODUCTION_TM = "wms-fulfillment";
    private static final int COMMITTED = 9;
    private static final int ROLLBACKED = 11;
    private static final int TIMEOUT_ROLLBACKED = 13;
    private static final int FENCE_COMMITTED = 2;

    private final JdbcTemplate audit;
    private final JdbcTemplate business;
    private final BusinessBarrierMapper attempts;

    TerminalEvidenceAdapter(JdbcTemplate audit, JdbcTemplate business, BusinessBarrierMapper attempts) {
        this.audit = audit;
        this.business = business;
        this.attempts = attempts;
    }

    /** 缺证据、身份不匹配、查询失败一律RECOVERY_PENDING；回滚终态DENIED；不得由XXL调用。 */
    Decision evaluate(String tenant, String attempt, long observedEpoch) {
        String xid = attempts.xid(tenant, attempt);
        Long epoch = attempts.epoch(tenant, attempt);
        if (xid == null || epoch == null || epoch != observedEpoch) {
            return Decision.RECOVERY_PENDING;
        }
        String participants = attempts.participants(tenant, attempt);
        if (participants == null || participants.isBlank()) {
            return Decision.RECOVERY_PENDING;
        }
        String[] warehouses = participants.split(",");
        if (warehouses.length == 0) {
            return Decision.RECOVERY_PENDING;
        }
        Evidence evidence;
        try {
            evidence = loadEvidence(xid);
        } catch (DataAccessException timeoutOrDenied) {
            return Decision.RECOVERY_PENDING;
        }
        if (evidence == null) {
            return Decision.RECOVERY_PENDING;
        }
        if (evidence.status == ROLLBACKED || evidence.status == TIMEOUT_ROLLBACKED) {
            return Decision.DENIED;
        }
        if (evidence.status != COMMITTED) {
            return Decision.RECOVERY_PENDING;
        }
        String expectedTm = attempts.expectedTm(tenant, attempt);
        String expectedGroup = attempts.expectedGroup(tenant, attempt);
        if (expectedTm == null || expectedGroup == null
                || !expectedTm.equals(evidence.applicationId)
                || !expectedGroup.equals(evidence.transactionGroup)) {
            return Decision.RECOVERY_PENDING;
        }
        for (String warehouse : warehouses) {
            if (!warehouseConfirmed(xid, warehouse.trim())) {
                return Decision.RECOVERY_PENDING;
            }
        }
        return Decision.ALLOW_ALLOCATED;
    }

    /** 仅ALLOW时写ALLOCATED Outbox；其他决定保持零记录。 */
    int releaseIfAllowed(String tenant, String attempt, long observedEpoch) {
        if (evaluate(tenant, attempt, observedEpoch) != Decision.ALLOW_ALLOCATED) {
            return 0;
        }
        return attempts.insertAllocated(tenant, attempt);
    }

    /** XXL执行线程禁止发起Confirm/Cancel或TM提交。 */
    void refusePhaseTwoFromJob() {
        throw new IllegalStateException("XXL_MUST_NOT_CONFIRM_OR_CANCEL");
    }

    /** 审计账号只允许SELECT；写入必须由数据库拒绝。 */
    void assertAuditIsReadOnly() {
        try {
            audit.update("INSERT INTO terminal_evidence(xid,application_id,transaction_service_group,terminal_status) "
                    + "VALUES('audit-write-forbidden','x','g',9)");
            throw new AssertionError("只读审计账号不得INSERT terminal_evidence");
        } catch (DataAccessException rejected) {
            Integer leaked = audit.queryForObject(
                    "SELECT COUNT(*) FROM terminal_evidence WHERE xid=?", Integer.class, "audit-write-forbidden");
            if (leaked != null && leaked > 0) {
                throw new AssertionError("审计拒绝写入后仍出现证据行", rejected);
            }
        }
    }

    private Evidence loadEvidence(String xid) {
        var rows = audit.query(
                "SELECT application_id, transaction_service_group, terminal_status FROM terminal_evidence WHERE xid=?",
                (rs, row) -> new Evidence(rs.getString(1), rs.getString(2), rs.getInt(3)),
                xid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean warehouseConfirmed(String xid, String warehouse) {
        if (warehouse.isEmpty()) {
            return false;
        }
        Integer status = business.query(
                "SELECT status FROM tcc_fence_log WHERE xid=? AND action_name=?",
                rs -> rs.next() ? rs.getInt(1) : null,
                xid, BusinessBarrierProbe.resource(warehouse));
        return status != null && status == FENCE_COMMITTED;
    }

    private record Evidence(String applicationId, String transactionGroup, int status) { }
}
