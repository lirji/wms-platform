package com.lrj.wms.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** issuer 为空时保持拒绝业务访问。 */
public final class OnWmsOidcDisabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String issuer = context.getEnvironment().getProperty("wms.oidc.issuer", "");
        return issuer == null || issuer.isBlank();
    }
}
