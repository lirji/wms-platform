package com.lrj.wms.fulfillment;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** admin 地址非空才启动执行器。 */
public final class OnXxlAdminConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String addresses = context.getEnvironment().getProperty("wms.xxl.admin-addresses", "");
        return addresses != null && !addresses.isBlank();
    }
}
