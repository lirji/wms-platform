package com.lrj.wms.inbound.configuration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** JDBC URL 非空才接库；空字符串必须视为未配置，避免 smoke 误连。 */
public final class OnInboundJdbcConfigured implements Condition {
    /** 在当前组合根提供 matches，使实例依赖沿用该模块已配置的数据源、时钟和运行参数。 */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String url = context.getEnvironment().getProperty("wms.inbound.datasource.url", "");
        return url != null && !url.isBlank();
    }
}
