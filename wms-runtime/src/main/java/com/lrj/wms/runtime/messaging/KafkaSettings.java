package com.lrj.wms.runtime.messaging;

import jakarta.validation.constraints.*;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 运行环境必须显式选择broker与隔离前缀，禁止把开发连接默认为生产连接。 */
@Validated
@ConfigurationProperties("wms.messaging")
public record KafkaSettings(@DefaultValue("false") boolean enabled, @DefaultValue("") String bootstrapServers,
        @DefaultValue("wms.local") @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String topicPrefix,
        @DefaultValue("PLAINTEXT") String securityProtocol, @DefaultValue("") String saslMechanism,
        @DefaultValue("") String saslJaasConfig) {
    @AssertTrue(message = "启用消息必须配置broker与有效安全协议")
    public boolean isConfigured() {
        return !enabled || bootstrapServers != null && !bootstrapServers.isBlank()
                && java.util.Set.of("PLAINTEXT", "SSL", "SASL_SSL", "SASL_PLAINTEXT").contains(securityProtocol);
    }

    /** 保留Kafka官方类型的机密属性，调用方不得输出这些Properties。 */
    public Properties connection() {
        if (!isConfigured() || bootstrapServers == null || bootstrapServers.isBlank()) throw new IllegalStateException("消息broker未配置");
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("security.protocol", securityProtocol);
        if (saslMechanism != null && !saslMechanism.isBlank()) properties.put("sasl.mechanism", saslMechanism);
        if (saslJaasConfig != null && !saslJaasConfig.isBlank()) properties.put("sasl.jaas.config", saslJaasConfig);
        return properties;
    }
    /** record默认toString会泄露JAAS机密，日志只保留非敏感状态。 */
    @Override public String toString() { return "KafkaSettings[enabled=" + enabled + ", topicPrefix=" + topicPrefix + "]"; }
}
