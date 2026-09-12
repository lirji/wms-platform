package com.lrj.wms.inventory.jobs;

import com.lrj.wms.inventory.tcc.TccReservationWatch;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 库存侧目录任务。只巡检/规划，禁止 Confirm/Cancel 或按 TTL 释放。
 * 分片领取与检查点留给 S7-02。
 */
@Component
public class InventoryCatalogJobs {
    private final TccReservationWatch watch;

    public InventoryCatalogJobs(ObjectProvider<TccReservationWatch> watches) {
        this.watch = watches == null ? null : watches.getIfAvailable();
    }

    @XxlJob(WmsJobCatalog.TCC_RESERVATION_WATCH)
    public void tccReservationWatch() {
        clearSchedulerContext();
        if (watch == null) {
            XxlJobHelper.log("tccReservationWatch skipped: no JDBC");
            return;
        }
        String[] scope = requireScope(2);
        watch.inspect(scope[0], scope[1]);
    }

    @XxlJob(WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP)
    public void expiryEligibilitySweep() {
        inspectOnly(WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP);
    }

    @XxlJob(WmsJobCatalog.SERIAL_TRANSFER_RECOVERY)
    public void serialTransferRecovery() {
        inspectOnly(WmsJobCatalog.SERIAL_TRANSFER_RECOVERY);
    }

    @XxlJob(WmsJobCatalog.STOCK_INTERNAL_RECONCILE)
    public void stockInternalReconcile() {
        inspectOnly(WmsJobCatalog.STOCK_INTERNAL_RECONCILE);
    }

    @XxlJob(WmsJobCatalog.EXTERNAL_RECONCILE_EXPORT)
    public void externalReconcileExport() {
        inspectOnly(WmsJobCatalog.EXTERNAL_RECONCILE_EXPORT);
    }

    @XxlJob(WmsJobCatalog.COUNT_APPLY_RECOVERY)
    public void countApplyRecovery() {
        inspectOnly(WmsJobCatalog.COUNT_APPLY_RECOVERY);
    }

    @XxlJob(WmsJobCatalog.ARCHIVE_PLANNER)
    public void archivePlanner() {
        inspectOnly(WmsJobCatalog.ARCHIVE_PLANNER);
    }

    @XxlJob(WmsJobCatalog.JOB_LEASE_RECOVERY)
    public void jobLeaseRecovery() {
        inspectOnly(WmsJobCatalog.JOB_LEASE_RECOVERY);
    }

    static void clearSchedulerContext() {
        RootContext.unbind();
        if (GlobalTransactionContext.getCurrent() != null) {
            throw new IllegalStateException("XXL不得持有TCC");
        }
    }

    private static void inspectOnly(String handler) {
        clearSchedulerContext();
        XxlJobHelper.log(handler + " inspect-only; shards in S7-02");
    }

    private static String[] requireScope(int parts) {
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            throw new IllegalArgumentException("任务参数必须带企业/范围");
        }
        String[] split = param.split(",");
        if (split.length < parts) {
            throw new IllegalArgumentException("任务参数不足");
        }
        return split;
    }
}
