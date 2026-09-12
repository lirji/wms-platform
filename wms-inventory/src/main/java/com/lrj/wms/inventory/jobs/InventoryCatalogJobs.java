package com.lrj.wms.inventory.jobs;

import com.lrj.wms.inventory.tcc.TccReservationWatch;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 库存侧目录任务按企业/仓执行有界批次，提交后下次触发续跑。
 * 禁止 Confirm/Cancel 或按 TTL 释放；缺执行器不得向调度器报告成功。
 */
@Component
public class InventoryCatalogJobs {
    private final TccReservationWatch watch;
    private final org.apache.ibatis.session.SqlSessionFactory sessions;

    private com.lrj.wms.inventory.serial.SerialRegistryHttpClient registry;
    @org.springframework.beans.factory.annotation.Autowired
    public void registry(ObjectProvider<com.lrj.wms.inventory.serial.SerialRegistryHttpClient> clients) { this.registry=clients.getIfAvailable(); }

    public InventoryCatalogJobs(ObjectProvider<TccReservationWatch> watches,
            ObjectProvider<org.apache.ibatis.session.SqlSessionFactory> sessions) {
        this.watch = watches == null ? null : watches.getIfAvailable();
        this.sessions = sessions.getIfAvailable();
    }

    @XxlJob(WmsJobCatalog.TCC_RESERVATION_WATCH)
    public void tccReservationWatch() {
        clearSchedulerContext();
        if (watch == null) {
            throw new IllegalStateException("TCC巡检未配置业务数据库");
        }
        String[] scope = requireScope(2);
        watch.inspect(scope[0], scope[1]);
    }

    @XxlJob(WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP)
    public void expiryEligibilitySweep() {
        clearSchedulerContext();
        String[] scope = requireScope(3);
        try (var session = requireSessions().openSession(false)) {
            var result = new ExpiryEligibilitySweep(session, java.time.Clock.systemUTC()).execute(scope[0], scope[1], scope[2]);
            session.commit();
            XxlJobHelper.log("expiry notices={}, hasMore={}", result.notices(), result.hasMore());
        }
    }

    @XxlJob(WmsJobCatalog.SERIAL_TRANSFER_RECOVERY)
    public void serialTransferRecovery() {
        clearSchedulerContext();
        String[] scope=requireScope(2);
        var released=new com.lrj.wms.inventory.serial.SerialReleaseRecoveryService(requireSessions(),java.time.Clock.systemUTC(),registry).execute(scope[0],scope[1]);
        var report=new com.lrj.wms.inventory.serial.SerialRecoveryService(requireSessions(),java.time.Clock.systemUTC(),registry,registry).execute(scope[0],scope[1],10);
        XxlJobHelper.log("serial recovered={}, failed={}, released={}, releaseFailed={}",report.completed(),report.failed(),released.completed(),released.failed());
        if(report.failed()+released.failed()>0) throw new IllegalStateException("登记恢复失败已保留HOLD及有界退避/隔离记录");
    }

    @XxlJob(WmsJobCatalog.STOCK_INTERNAL_RECONCILE)
    public void stockInternalReconcile() {
        clearSchedulerContext();
        String[] scope = requireScope(3);
        try (var session = requireSessions().openSession(org.apache.ibatis.session.TransactionIsolationLevel.REPEATABLE_READ)) {
            var result = new com.lrj.wms.inventory.recon.StockInternalReconcile(session, java.time.Clock.systemUTC())
                    .execute(scope[0], scope[1], scope[2]);
            session.commit();
            XxlJobHelper.log("recon scanned={}, opened={}, closed={}, cycleCompleted={}, watermarksComplete={}",
                    result.scanned(), result.opened(), result.closed(), result.cycleCompleted(), result.watermarksComplete());
        }
    }

    @XxlJob(WmsJobCatalog.EXTERNAL_RECONCILE_EXPORT)
    public void externalReconcileExport() {
        clearSchedulerContext();
        String[] scope = requireScope(2);
        try (var session = requireSessions().openSession(false)) {
            var snapshot = session.getMapper(com.lrj.wms.inventory.recon.SnapshotMapper.class).nextExporting(scope[0], scope[1]);
            if (snapshot == null) { session.commit(); return; }
            var watermarks = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(String.valueOf(snapshot.get("source_watermarks")));
            var result = new com.lrj.wms.inventory.recon.SnapshotExportService(session, java.time.Clock.systemUTC())
                    .export(scope[0], scope[1], String.valueOf(snapshot.get("cutoff_id")),
                            java.sql.Timestamp.from(com.lrj.wms.inventory.inventory.domain.ExpiryPolicy.instantOf(snapshot.get("closed_at"))),
                            watermarks.path("source").asString(), watermarks.path("posting").asString(), watermarks.path("receipt").asString());
            session.commit();
            XxlJobHelper.log("snapshot={}, state={}", result.get("snapshotId"), result.get("state"));
        }
    }

    @XxlJob(WmsJobCatalog.COUNT_APPLY_RECOVERY)
    public void countApplyRecovery() {
        clearSchedulerContext();
        String[] scope = requireScope(3);
        var report = new com.lrj.wms.inventory.count.CountApplyRecovery(requireSessions(), java.time.Clock.systemUTC(),registry)
                .execute(scope[0], scope[1], scope[2]);
        XxlJobHelper.log("count applied={}, failed={}", report.applied(), report.failed());
        if (report.failed() > 0) throw new IllegalStateException("盘点存在失败行，已记录退避与错误码，计划保持冻结");
    }

    @XxlJob(WmsJobCatalog.ARCHIVE_PLANNER)
    public void archivePlanner() {
        clearSchedulerContext();
        String[] scope = requireScope(5);
        try (var session = requireSessions().openSession(false)) {
            var result = new com.lrj.wms.inventory.archive.ArchivePlanner(session, java.time.Clock.systemUTC()).execute(
                    scope[0],scope[1],scope[2],java.time.Instant.parse(scope[3]),scope[4],"job:archivePlanner");
            session.commit();
            XxlJobHelper.log("archive plan={}, state={}, candidates={}", result.get("planId"),result.get("state"),result.get("candidateCount"));
        }
    }

    @XxlJob(WmsJobCatalog.JOB_LEASE_RECOVERY)
    public void jobLeaseRecovery() {
        clearSchedulerContext();
        String[] scope = requireScope(2);
        try (var session = requireSessions().openSession(false)) {
            int reclaimed = new JobRunService(session, java.time.Clock.systemUTC()).reclaimExpired(scope[0], scope[1]);
            session.commit();
            XxlJobHelper.log("reclaimed={}", reclaimed);
        }
    }

    static void clearSchedulerContext() {
        RootContext.unbind();
        if (GlobalTransactionContext.getCurrent() != null) {
            throw new IllegalStateException("XXL不得持有TCC");
        }
    }

    private org.apache.ibatis.session.SqlSessionFactory requireSessions() {
        if (sessions == null) throw new IllegalStateException("后台任务未配置业务数据库");
        return sessions;
    }

    private static String[] requireScope(int parts) {
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            throw new IllegalArgumentException("任务参数必须带企业/范围");
        }
        String[] split = param.split(",");
        if (split.length != parts || java.util.Arrays.stream(split).anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("任务参数不足");
        }
        return split;
    }
}
