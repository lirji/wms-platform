package com.lrj.wms.security;

import java.util.ArrayList;
import java.util.List;
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

/**
 * issuer 配置后校验 Casdoor JWT；未配置时拒绝全部业务请求，绝不免认证回退。
 */
@AutoConfiguration
@EnableConfigurationProperties(WmsOidcProperties.class)
public class WmsSecurityAutoConfiguration {
    /** 仅真正配置本库恢复服务时公开入口，不能靠通用HTTP入口跨库恢复消息。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.lrj.wms.runtime.messaging.MessageRecoveryService.class)
    MessageRecoveryController messageRecoveryController(com.lrj.wms.runtime.messaging.MessageRecoveryService service) {
        return new MessageRecoveryController(service);
    }

    /** 无 issuer 时只放行健康检查。 */
    @Bean
    @Conditional(OnWmsOidcDisabled.class)
    SecurityFilterChain denyAllBusiness(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
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
    @Conditional(OnWmsOidcEnabled.class)
    SecurityFilterChain oidcResourceServer(HttpSecurity http, JwtDecoder jwtDecoder,
            com.lrj.wms.runtime.web.AdmissionGate admissionGate) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").access((authentication, context) -> {
                            Object principal = authentication.get().getPrincipal();
                            return new org.springframework.security.authorization.AuthorizationDecision(principal instanceof Jwt jwt
                                    && WmsJwtAuthorities.operationScopes(jwt).contains("observability.read"));
                        })
                        .anyRequest().authenticated())
                .addFilterAfter(new OperationScopeFilter(),
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantAdmissionFilter(admissionGate), OperationScopeFilter.class)
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)
                        .jwtAuthenticationConverter(WmsJwtAuthorities.converter())))
                .build();
    }

    /** JWKS 验签、issuer，可选 audience。测试可提供自己的 JwtDecoder。 */
    @Bean
    @Conditional(OnWmsOidcEnabled.class)
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(WmsOidcProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.resolveJwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtIssuerValidator(properties.getIssuer()));
        if (properties.getClientId() != null && !properties.getClientId().isBlank()) {
            String audience = properties.getClientId();
            validators.add(jwt -> jwt.getAudience().contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "aud 需含资源客户端", null)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }
}
