package com.lrj.wms.fulfillment;

import java.util.List;
import java.util.Map;

/**
 * 同步已观察到的 TC 终态并补齐 ALLOCATED/Outbox。二阶段仍由 TC 负责，本任务不得 Confirm/Cancel。
 */
public final class AllocationRecoverySweep {
    public static final String HANDLER = "allocationRecoverySweep";

    private final FulfillmentService fulfillment;
    private final TcStatusPort tcStatus;
    private final org.apache.ibatis.session.SqlSessionFactory sessions;
    private final TcEvidenceScope scope;
    private final java.time.Clock clock;
    private final com.lrj.wms.runtime.web.AdmissionGate admission = new com.lrj.wms.runtime.web.AdmissionGate(
            new com.lrj.wms.runtime.web.AdmissionBudget(4, 1, 8, 2));

    public AllocationRecoverySweep(FulfillmentService fulfillment, TcStatusPort tcStatus) {
        this.sessions = null;
        this.scope = null;
        this.clock = java.time.Clock.systemUTC();
        this.fulfillment = fulfillment;
        this.tcStatus = tcStatus == null ? new UnavailableTcStatusPort() : tcStatus;
    }

    /** 生产入口逐项短事务；TC网络读取在业务会话外，检查点跨进程保存。 */
    public AllocationRecoverySweep(org.apache.ibatis.session.SqlSessionFactory sessions,
            TcStatusPort tcStatus, TcEvidenceScope scope, java.time.Clock clock) {
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.tcStatus = java.util.Objects.requireNonNull(tcStatus);
        this.scope = java.util.Objects.requireNonNull(scope);
        this.clock = clock;
        this.fulfillment = null;
    }

    /** 先写观察副本，再复用屏障恢复。端口为空则保持 RECOVERY_PENDING。 */
    public Report execute(String enterpriseId) {
        if (enterpriseId == null || enterpriseId.isBlank()) {
            throw new IllegalArgumentException("恢复扫描必须带企业");
        }
        if (enterpriseId.length() > 64) throw new IllegalArgumentException("企业标识过长");
        if (sessions != null) return executePersistent(enterpriseId);
        int observed = 0;
        List<Map<String, Object>> open = fulfillment.listOpenBoundAttempts(enterpriseId);
        for (Map<String, Object> attempt : open) {
            String evidence = attempt.get("tc_terminal_evidence") == null ? ""
                    : String.valueOf(attempt.get("tc_terminal_evidence"));
            if (!evidence.isBlank()) {
                continue;
            }
            String xid = String.valueOf(attempt.get("xid"));
            var observation = tcStatus.read(xid);
            if (observation.isEmpty()) {
                continue;
            }
            fulfillment.observeTc(enterpriseId, String.valueOf(attempt.get("id")), observation.get().status(),
                    observation.get().evidence());
            observed++;
        }
        int recovered = fulfillment.recoverReadyBarriers(enterpriseId);
        return new Report(open.size(), observed, recovered);
    }

    /** 每企业一次在途扫描，每轮最多20项/20秒；检查点更新与本项业务更新同事务。 */
    private Report executePersistent(String enterpriseId) {
        var permit = admission.acquire(enterpriseId);
        if (permit == null) throw new FulfillmentException("RECOVERY_BUSY", "分配恢复已达本实例并发或频率上限");
        try (permit) {
            Map<String, Object> cursor;
            List<Map<String, Object>> page;
            try (var session = sessions.openSession(false)) {
                var mapper = session.getMapper(AllocationRecoveryMapper.class);
                mapper.ensureCursor(enterpriseId, now());
                cursor = mapper.cursor(enterpriseId);
                page = mapper.page(enterpriseId, (String) cursor.get("last_attempt_id"));
                session.commit();
            }
            long version = ((Number) cursor.get("version")).longValue();
            if (page.isEmpty()) {
                advance(enterpriseId, version, null);
                return new Report(0, 0, 0);
            }
            int observed = 0, recovered = 0, visited = 0;
            String failure = null;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            for (var snapshot : page) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) break;
                String id = String.valueOf(snapshot.get("id"));
                try {
                    Map<String, Object> binding;
                    try (var session = sessions.openSession()) {
                        binding = session.getMapper(AllocationRecoveryMapper.class).binding(enterpriseId, id);
                    }
                    requireBinding(snapshot, binding);
                    // 此时没有打开的业务事务；网络故障不能占用库存或履约行锁。
                    var observation = tcStatus.read(String.valueOf(snapshot.get("xid")));
                    try (var session = sessions.openSession(false)) {
                        var mapper = session.getMapper(FulfillmentMapper.class);
                        var current = mapper.lockAttempt(enterpriseId, id);
                        if (current == null || !java.util.Objects.equals(snapshot.get("xid"), current.get("xid"))
                                || !java.util.Objects.equals(snapshot.get("launch_epoch"), current.get("launch_epoch"))
                                || !java.util.Objects.equals(snapshot.get("participant_set_hash"), current.get("participant_set_hash"))) {
                            throw new FulfillmentException("TC_OBSERVATION_STALE", "TC观察返回时attempt身份或代际已变化");
                        }
                        var service = new FulfillmentService(session, clock);
                        boolean fresh = current.get("tc_terminal_evidence") == null;
                        int changed = 0;
                        if (observation.isPresent()) {
                            service.observeTc(enterpriseId, id, observation.get().status(), observation.get().evidence());
                            if ("Committed".equals(observation.get().status())) {
                                try { service.markAllocated(enterpriseId, id); changed = 1; }
                                catch (FulfillmentException pending) {
                                    if (!FulfillmentService.isRecoveryPending(pending)) throw pending;
                                }
                            }
                        }
                        int advanced = session.getMapper(AllocationRecoveryMapper.class)
                                .advance(enterpriseId, version, id, now());
                        session.commit();
                        visited++;
                        if (fresh && observation.isPresent()) observed++;
                        recovered += changed;
                        if (advanced == 0) break;
                        version++;
                    }
                } catch (FulfillmentException error) {
                    // 记录调度失败，同时推进本项，避免一个缺来源的历史attempt永久饿死后续项。
                    failure = error.code();
                    visited++;
                    if (!advance(enterpriseId, version, id)) break;
                    version++;
                }
            }
            if (failure != null) throw new FulfillmentException(failure, "本轮部分分配仍待恢复；已成功项和检查点已持久化");
            return new Report(visited, observed, recovered);
        }
    }

    private void requireBinding(Map<String, Object> snapshot, Map<String, Object> binding) {
        if (binding == null || !scope.clusterId().equals(binding.get("cluster_id"))
                || !scope.applicationId().equals(binding.get("application_id"))
                || !scope.transactionGroup().equals(binding.get("transaction_group"))
                || !java.util.Objects.equals(snapshot.get("xid"), binding.get("xid"))
                || !java.util.Objects.equals(snapshot.get("launch_epoch"), binding.get("launch_epoch"))) {
            throw new FulfillmentException("TC_BINDING_MISSING", "缺少匹配的持久化TC集群与TM来源，禁止猜测回填");
        }
    }

    private boolean advance(String enterpriseId, long version, String id) {
        try (var session = sessions.openSession(false)) {
            int changed = session.getMapper(AllocationRecoveryMapper.class).advance(enterpriseId, version, id, now());
            session.commit();
            return changed == 1;
        }
    }
    private java.sql.Timestamp now() { return java.sql.Timestamp.from(clock.instant()); }

    /** XXL 不得进入二阶段。 */
    public static void refusePhaseTwo() {
        throw new IllegalStateException("XXL不得Confirm或Cancel");
    }

    public record Report(int openAttempts, int newlyObserved, int recovered) {
    }
}
