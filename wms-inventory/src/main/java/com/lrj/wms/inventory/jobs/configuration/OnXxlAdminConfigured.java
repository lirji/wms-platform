package com.lrj.wms.inventory.jobs.configuration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** admin 地址非空才启动执行器，避免 smoke 连接调度中心。 */
public final class OnXxlAdminConfigured implements Condition {
    /** 在当前组合根提供 matches，使实例依赖沿用该模块已配置的数据源、时钟和运行参数。 */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String addresses = context.getEnvironment().getProperty("wms.xxl.admin-addresses", "");
        return addresses != null && !addresses.isBlank();
    }
}
