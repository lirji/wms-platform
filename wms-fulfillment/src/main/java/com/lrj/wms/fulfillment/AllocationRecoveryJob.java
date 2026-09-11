package com.lrj.wms.fulfillment;

import com.xxl.job.core.handler.annotation.XxlJob;

/** XXL 入口：同步观察并补齐 Outbox。不注册执行器，避免 smoke 连接 admin。 */
public final class AllocationRecoveryJob {
    private final AllocationRecoverySweep sweep;
    private final String enterpriseId;

    public AllocationRecoveryJob(AllocationRecoverySweep sweep, String enterpriseId) {
        this.sweep = sweep;
        this.enterpriseId = enterpriseId;
    }

    @XxlJob(AllocationRecoverySweep.HANDLER)
    public AllocationRecoverySweep.Report execute() {
        return sweep.execute(enterpriseId);
    }
}
