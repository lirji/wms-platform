package com.lrj.wms.inventory;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** JDBC URL 非空才接库；空字符串必须视为未配置，避免 smoke 误连。 */
public final class OnInventoryJdbcConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String url = context.getEnvironment().getProperty("wms.inventory.datasource.url", "");
        return url != null && !url.isBlank();
    }
}
