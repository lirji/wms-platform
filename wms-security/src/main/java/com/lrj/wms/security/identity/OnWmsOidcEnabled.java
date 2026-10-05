package com.lrj.wms.security.identity;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** issuer 非空才启用资源服务器。 */
public final class OnWmsOidcEnabled implements Condition {
    /** 只根据该条件定义的配置判断启用路径，避免缺失配置误启用替代实现。 */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String issuer = context.getEnvironment().getProperty("wms.oidc.issuer", "");
        return issuer != null && !issuer.isBlank();
    }
}
