package com.lrj.wms.fulfillment;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** XXL 入口：同步观察并补齐 Outbox。执行器仅在配置 admin 后启动。 */
@Component
public class AllocationRecoveryJob {
    private final AllocationRecoverySweep sweep;
    private final String enterpriseId;

    @Autowired
    public AllocationRecoveryJob(ObjectProvider<AllocationRecoverySweep> sweeps) {
        this(sweeps.getIfAvailable(), null);
    }

    public AllocationRecoveryJob(AllocationRecoverySweep sweep, String enterpriseId) {
        this.sweep = sweep;
        this.enterpriseId = enterpriseId;
    }

    @XxlJob(AllocationRecoverySweep.HANDLER)
    public AllocationRecoverySweep.Report execute() {
        if (sweep == null) {
            XxlJobHelper.log("allocationRecoverySweep skipped: no JDBC");
            return new AllocationRecoverySweep.Report(0, 0, 0);
        }
        String scope = enterpriseId;
        if (scope == null || scope.isBlank()) {
            scope = XxlJobHelper.getJobParam();
        }
        return sweep.execute(scope);
    }
}
