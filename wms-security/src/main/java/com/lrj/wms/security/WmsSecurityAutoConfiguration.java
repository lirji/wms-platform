package com.lrj.wms.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * issuer 配置后校验 Casdoor JWT；未配置时拒绝全部业务请求，绝不免认证回退。
 */
@AutoConfiguration
@EnableConfigurationProperties({WmsOidcProperties.class, WmsInternalOidcProperties.class})
public class WmsSecurityAutoConfiguration {
    /** 机器协议单独验签和校验可信主体，Owner继续检查原scope、企业、仓和事务标识。 */
    @Bean
    @org.springframework.core.annotation.Order(0)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "wms.iam.enabled",
            havingValue = "true")
    SecurityFilterChain internalMachineResourceServer(
            HttpSecurity http, WmsInternalOidcProperties properties) throws Exception {
        http.securityMatcher("/internal/wms/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (!properties.configured())
            return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new JwtIssuerValidator(properties.issuer()),
                        jwt ->
                                properties.allowedSubjects().contains(jwt.getSubject())
                                                && jwt.getAudience().contains(properties.clientId())
                                                // 旧机器令牌可能没有purpose声明；明确标识为ID Token的令牌仍必须拒绝。
                                                && !"id-token"
                                                        .equals(jwt.getClaims().get("tokenType"))
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                                new OAuth2Error(
                                                        "invalid_token", "机器身份不受信任", null))));
        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                        jwt ->
                                                jwt.decoder(decoder)
                                                        .jwtAuthenticationConverter(
                                                                WmsJwtAuthorities.converter())))
                .build();
    }

    /** 开关开启时配置缺失必须失败；不构造旧JWT权限的OR回退。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "wms.iam.enabled",
            havingValue = "true")
    WmsCentralAuthorization centralAuthorization(
            org.springframework.core.env.Environment environment, WmsOidcProperties oidc) {
        return new WmsCentralAuthorization(
                WmsCentralSettings.read(environment.getProperty("wms.iam.configuration"), oidc));
    }

    /** 所有模式都通过实际后端报告本人提示，避免前端猜测运行权限模式。 */
    @Bean
    WmsMeAccessController meAccessController(
            org.springframework.beans.factory.ObjectProvider<WmsCentralAuthorization> central) {
        return new WmsMeAccessController(central.getIfAvailable());
    }

    @Bean
    WmsCentralErrors centralErrors() {
        return new WmsCentralErrors();
    }

    /** 仅真正配置本库恢复服务时公开入口，不能靠通用HTTP入口跨库恢复消息。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            com.lrj.wms.runtime.messaging.MessageRecoveryService.class)
    MessageRecoveryController messageRecoveryController(
            com.lrj.wms.runtime.messaging.MessageRecoveryService service) {
        return new MessageRecoveryController(service);
    }

    /** 无 issuer 时只放行健康检查。 */
    @Bean
    @Conditional(OnWmsOidcDisabled.class)
    SecurityFilterChain denyAllBusiness(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }

    /** 未启用 OIDC 时禁止生成默认用户。 */
    @Bean
    @Conditional(OnWmsOidcDisabled.class)
    @ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService noBootstrapUsers() {
        return username -> {
            throw new UsernameNotFoundException("未启用本地用户登录");
        };
    }

    /** Casdoor 资源服务器。 */
    @Bean
    @org.springframework.core.annotation.Order(1)
    @Conditional(OnWmsOidcEnabled.class)
    SecurityFilterChain oidcResourceServer(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            com.lrj.wms.runtime.web.AdmissionGate admissionGate,
            org.springframework.beans.factory.ObjectProvider<WmsCentralAuthorization> central)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/actuator/metrics", "/actuator/metrics/**")
                                        .access(
                                                (authentication, context) -> {
                                                    Object principal =
                                                            authentication.get().getPrincipal();
                                                    return new org.springframework.security
                                                            .authorization.AuthorizationDecision(
                                                            central.getIfAvailable() == null
                                                                    && principal instanceof Jwt jwt
                                                                    && WmsJwtAuthorities
                                                                            .operationScopes(jwt)
                                                                            .contains(
                                                                                    "observability.read"));
                                                })
                                        .anyRequest()
                                        .authenticated())
                .addFilterAfter(
                        new OperationScopeFilter(central.getIfAvailable()),
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new TenantAdmissionFilter(admissionGate), OperationScopeFilter.class)
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                        jwt ->
                                                jwt.decoder(jwtDecoder)
                                                        .jwtAuthenticationConverter(
                                                                WmsJwtAuthorities.converter())))
                .build();
    }

    /** JWKS 验签、issuer，可选 audience。测试可提供自己的 JwtDecoder。 */
    @Bean
    @Conditional(OnWmsOidcEnabled.class)
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            WmsOidcProperties properties, org.springframework.core.env.Environment environment) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(properties.resolveJwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtIssuerValidator(properties.getIssuer()));
        if (properties.getClientId() != null && !properties.getClientId().isBlank()) {
            String audience = properties.getClientId();
            validators.add(
                    jwt ->
                            jwt.getAudience().contains(audience)
                                    ? OAuth2TokenValidatorResult.success()
                                    : OAuth2TokenValidatorResult.failure(
                                            new OAuth2Error("invalid_token", "aud 需含资源客户端", null)));
        }
        if (environment.getProperty("wms.iam.enabled", Boolean.class, false)) {
            // 中央模式在协议入口也区分Access/ID Token，避免内部路由绕过中央调用时误接受ID身份。
            validators.add(
                    jwt ->
                            "access-token".equals(jwt.getClaims().get("tokenType"))
                                    ? OAuth2TokenValidatorResult.success()
                                    : OAuth2TokenValidatorResult.failure(
                                            new OAuth2Error(
                                                    "invalid_token", "需要Access Token", null)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }
}
