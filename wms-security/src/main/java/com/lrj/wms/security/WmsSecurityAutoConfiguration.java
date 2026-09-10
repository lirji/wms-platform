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
    SecurityFilterChain oidcResourceServer(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
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
