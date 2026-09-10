package com.lrj.wms.inbound;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** S0只开放健康端点；正式身份接入前，不提供默认账户或开放业务接口。 */
@Configuration
class BootstrapSecurity {
    /** 在OIDC接入前明确拒绝业务请求，避免无认证的启动配置进入共享环境。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().denyAll()).build();
    }

    /** 禁止自动生成开发默认用户；本阶段没有可登录账户。 */
    @Bean
    UserDetailsService noBootstrapUsers() {
        return username -> { throw new UsernameNotFoundException("S0阶段未启用用户登录"); };
    }
}
