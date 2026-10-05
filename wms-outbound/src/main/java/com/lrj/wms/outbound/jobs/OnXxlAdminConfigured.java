package com.lrj.wms.outbound.jobs;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** admin 地址非空才启动执行器。 */
public final class OnXxlAdminConfigured implements Condition {
    /** 只根据该条件定义的配置判断启用路径，避免缺失配置误启用替代实现。 */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String addresses = context.getEnvironment().getProperty("wms.xxl.admin-addresses", "");
        return addresses != null && !addresses.isBlank();
    }
}
