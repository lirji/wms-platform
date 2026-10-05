package com.lrj.wms.fulfillment.recovery.configuration;

import com.lrj.wms.fulfillment.domain.FulfillmentException;
import com.lrj.wms.fulfillment.orchestration.AllocationRecoverySweep;
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

    /** 显式接收 AllocationRecoveryJob 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    @Autowired
    public AllocationRecoveryJob(ObjectProvider<AllocationRecoverySweep> sweeps) {
        this(sweeps.getIfAvailable(), null);
    }

    /** 显式接收 AllocationRecoveryJob 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public AllocationRecoveryJob(AllocationRecoverySweep sweep, String enterpriseId) {
        this.sweep = sweep;
        this.enterpriseId = enterpriseId;
    }

    /** 在当前组合根提供 execute，使实例依赖沿用该模块已配置的数据源、时钟和运行参数。 */
    @XxlJob(AllocationRecoverySweep.HANDLER)
    public AllocationRecoverySweep.Report execute() {
        if (sweep == null) {
            XxlJobHelper.handleFail("ALLOCATION_RECOVERY_UNAVAILABLE");
            throw new IllegalStateException("缺少真实数据库或TC只读审计，分配恢复不能报告成功");
        }
        String scope = enterpriseId;
        if (scope == null || scope.isBlank()) {
            scope = XxlJobHelper.getJobParam();
        }
        try {
            return sweep.execute(scope);
        } catch (RuntimeException error) {
            XxlJobHelper.handleFail(
                    error instanceof FulfillmentException failure
                            ? failure.code()
                            : "ALLOCATION_RECOVERY_FAILED");
            throw error;
        }
    }
}
