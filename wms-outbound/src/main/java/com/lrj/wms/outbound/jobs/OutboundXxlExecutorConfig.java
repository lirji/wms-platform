package com.lrj.wms.outbound.jobs;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 仅显式配置 admin 时注册执行器。 */
@Configuration
@Conditional(OnXxlAdminConfigured.class)
class OutboundXxlExecutorConfig {
    @Bean
    XxlJobSpringExecutor outboundXxlJobExecutor(Environment environment) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(environment.getProperty("wms.xxl.admin-addresses"));
        executor.setAppname(environment.getProperty("wms.xxl.app-name", "wms-outbound"));
        executor.setAccessToken(environment.getProperty("wms.xxl.access-token", ""));
        executor.setPort(Integer.parseInt(environment.getProperty("wms.xxl.port", "9996")));
        executor.setLogPath(environment.getProperty("wms.xxl.log-path", "target/xxl-outbound-logs"));
        executor.setLogRetentionDays(1);
        return executor;
    }
}
