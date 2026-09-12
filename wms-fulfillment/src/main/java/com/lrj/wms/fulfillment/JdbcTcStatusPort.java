package com.lrj.wms.fulfillment;

import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.session.SqlSessionFactory;
import tools.jackson.databind.json.JsonMapper;

/** 读取TC持久化终态；绝不调用getStatus补造证据，也不持有履约业务连接。 */
public final class JdbcTcStatusPort implements TcStatusPort, AutoCloseable, com.lrj.wms.runtime.observability.RuntimeDependencyCheck {
    private final SqlSessionFactory auditSessions;
    private final TcEvidenceScope scope;
    private final AutoCloseable ownedPool;
    private long healthCheckedAt;
    private org.springframework.boot.health.contributor.Health cachedHealth = org.springframework.boot.health.contributor.Health.outOfService().build();

    public JdbcTcStatusPort(SqlSessionFactory auditSessions, TcEvidenceScope scope, AutoCloseable ownedPool) {
        this.auditSessions = auditSessions;
        this.scope = scope;
        this.ownedPool = ownedPool;
    }

    public TcEvidenceScope scope() { return scope; }

    /** 数据库失败抛出可识别错误供调度报警；缺行属于正常待恢复，不能伪报已提交。 */
    @Override
    public Optional<Observation> read(String xid) {
        if (xid == null || xid.isBlank() || xid.length() > 128) throw new IllegalArgumentException("无效XID");
        Map<String, Object> row;
        try (var session = auditSessions.openSession()) {
            session.getConnection().setReadOnly(true);
            row = session.getMapper(TcEvidenceMapper.class).find(xid);
        } catch (RuntimeException | java.sql.SQLException unavailable) {
            throw new FulfillmentException("TC_AUDIT_UNAVAILABLE", "TC只读审计不可用，保持恢复等待");
        }
        if (row == null) return Optional.empty();
        if (!scope.applicationId().equals(row.get("application_id"))
                || !scope.transactionGroup().equals(row.get("transaction_service_group"))
                || !xid.equals(row.get("xid"))) {
            throw new FulfillmentException("TC_EVIDENCE_IDENTITY_MISMATCH", "TC终态不属于配置的TM应用或事务分组");
        }
        int code = row.get("terminal_status") instanceof Number number ? number.intValue() : -1;
        String status = switch (code) {
            case 9 -> "Committed";
            case 11 -> "Rollbacked";
            case 13 -> "TimeoutRollbacked";
            default -> throw new FulfillmentException("INVALID_TC_EVIDENCE", "未知TC终态码不能放行");
        };
        String evidence = JsonMapper.builder().build().writeValueAsString(Map.of("xid", xid, "status", code,
                "clusterId", scope.clusterId(), "applicationId", scope.applicationId(),
                "transactionGroup", scope.transactionGroup()));
        return Optional.of(new Observation(status, evidence));
    }

    /** 就绪探针最多每5秒查询一次，使用同一有界审计池并验证真实表权限。 */
    @Override
    public synchronized org.springframework.boot.health.contributor.Health health() {
        long now = System.nanoTime();
        if (healthCheckedAt != 0 && now - healthCheckedAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) return cachedHealth;
        try (var session = auditSessions.openSession()) {
            session.getConnection().setReadOnly(true);
            session.getMapper(TcEvidenceMapper.class).available();
            cachedHealth = org.springframework.boot.health.contributor.Health.up().build();
        } catch (RuntimeException | java.sql.SQLException unavailable) {
            cachedHealth = org.springframework.boot.health.contributor.Health.down().build();
        }
        healthCheckedAt = System.nanoTime();
        return cachedHealth;
    }

    /** 专属审计池归此适配器所有，不能关闭履约业务池。 */
    @Override
    public void close() throws Exception { if (ownedPool != null) ownedPool.close(); }
}
