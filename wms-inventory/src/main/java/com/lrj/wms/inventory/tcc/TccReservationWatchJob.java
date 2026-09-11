package com.lrj.wms.inventory.tcc;

import com.xxl.job.core.handler.annotation.XxlJob;

/** XXL 入口：只巡检预占。不注册执行器，避免 smoke 连接 admin。 */
public final class TccReservationWatchJob {
    private final TccReservationWatch watch;
    private final String enterpriseId;
    private final String warehouseId;

    public TccReservationWatchJob(TccReservationWatch watch, String enterpriseId, String warehouseId) {
        this.watch = watch;
        this.enterpriseId = enterpriseId;
        this.warehouseId = warehouseId;
    }

    @XxlJob(TccReservationWatch.HANDLER)
    public TccReservationWatch.Report execute() {
        return watch.inspect(enterpriseId, warehouseId);
    }
}
