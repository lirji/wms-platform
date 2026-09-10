package com.lrj.wms.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** issuer 非空才启用资源服务器。 */
public final class OnWmsOidcEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String issuer = context.getEnvironment().getProperty("wms.oidc.issuer", "");
        return issuer != null && !issuer.isBlank();
    }
}
