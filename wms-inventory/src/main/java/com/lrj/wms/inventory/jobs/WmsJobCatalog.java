package com.lrj.wms.inventory.jobs;

import java.util.List;

/**
 * 设计任务目录的稳定 BEAN handler 名。XXL 只负责触发，业务进度在各服务库。
 * 不是生产调度锁定。
 */
public final class WmsJobCatalog {
    public static final String TCC_RESERVATION_WATCH = "tccReservationWatch";
    public static final String ALLOCATION_RECOVERY_SWEEP = "allocationRecoverySweep";
    public static final String EXPIRY_ELIGIBILITY_SWEEP = "expiryEligibilitySweep";
    public static final String SERIAL_TRANSFER_RECOVERY = "serialTransferRecovery";
    public static final String DEVICE_UNKNOWN_RESULT_SWEEP = "deviceUnknownResultSweep";
    public static final String STOCK_INTERNAL_RECONCILE = "stockInternalReconcile";
    public static final String EXTERNAL_RECONCILE_EXPORT = "externalReconcileExport";
    public static final String COUNT_APPLY_RECOVERY = "countApplyRecovery";
    public static final String ARCHIVE_PLANNER = "archivePlanner";
    public static final String JOB_LEASE_RECOVERY = "jobLeaseRecovery";

    public static final List<String> HANDLERS = List.of(
            TCC_RESERVATION_WATCH,
            ALLOCATION_RECOVERY_SWEEP,
            EXPIRY_ELIGIBILITY_SWEEP,
            SERIAL_TRANSFER_RECOVERY,
            DEVICE_UNKNOWN_RESULT_SWEEP,
            STOCK_INTERNAL_RECONCILE,
            EXTERNAL_RECONCILE_EXPORT,
            COUNT_APPLY_RECOVERY,
            ARCHIVE_PLANNER,
            JOB_LEASE_RECOVERY);

    private WmsJobCatalog() {
    }
}
