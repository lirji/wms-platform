package com.lrj.wms.inventory.tcc.jobs;

import com.lrj.wms.inventory.tcc.application.TccReservationWatch;
import com.xxl.job.core.handler.annotation.XxlJob;

/** XXL 入口：只巡检预占。不注册执行器，避免 smoke 连接 admin。 */
public final class TccReservationWatchJob {
    private final TccReservationWatch watch;
    private final String enterpriseId;
    private final String warehouseId;

    /** 显式接收 TccReservationWatchJob 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public TccReservationWatchJob(
            TccReservationWatch watch, String enterpriseId, String warehouseId) {
        this.watch = watch;
        this.enterpriseId = enterpriseId;
        this.warehouseId = warehouseId;
    }

    /** 调度入口沿用当前任务的执行规则与领取身份，不能绕过业务工作器。 */
    @XxlJob(TccReservationWatch.HANDLER)
    public TccReservationWatch.Report execute() {
        return watch.inspect(enterpriseId, warehouseId);
    }
}
