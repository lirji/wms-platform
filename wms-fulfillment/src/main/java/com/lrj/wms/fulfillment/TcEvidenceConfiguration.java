package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** 显式开启才连接TC审计；专属池不作为通用DataSource暴露，不运行TC迁移或写入。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.tc.audit.enabled",havingValue="true")
public class TcEvidenceConfiguration {
    @Bean
    TcEvidenceScope tcEvidenceScope(org.springframework.core.env.Environment env) {
        return new TcEvidenceScope(env.getRequiredProperty("wms.tc.audit.cluster-id"), "wms-fulfillment",
                env.getRequiredProperty("wms.tc.audit.transaction-group"));
    }

    /** 每实例最多4个审计连接；获取500ms、语句1s、网络1.5s，失败直接待恢复。 */
    @Bean(destroyMethod="close")
    JdbcTcStatusPort tcStatusPort(org.springframework.core.env.Environment env, TcEvidenceScope scope) {
        String url = env.getRequiredProperty("wms.tc.audit.jdbc-url");
        if (env.getRequiredProperty("wms.tc.audit.username").isBlank()
                || env.getRequiredProperty("wms.tc.audit.password").isBlank()) {
            throw new IllegalArgumentException("TC审计必须提供受控专用凭据");
        }
        // Connector/J的URL参数可能优先于连接属性；拒绝绕过此适配器的固定网络预算。
        int query = url.indexOf('?');
        if (query >= 0) for (String parameter : url.substring(query + 1).split("&")) {
            var pair = parameter.split("=", 2);
            String key = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
            if ("connectTimeout".equalsIgnoreCase(key) || "socketTimeout".equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("TC审计URL不能覆盖固定超时预算");
            }
        }
        var pool = RuntimeDataSources.create("tc-audit", url,
                env.getRequiredProperty("wms.tc.audit.username"), env.getRequiredProperty("wms.tc.audit.password"),
                new DatabaseBudget(4,0,500,250,1,500,1500));
        try {
            var config = new Configuration(new Environment("tc-audit", new JdbcTransactionFactory(), pool));
            config.setDefaultStatementTimeout(1);
            config.addMapper(TcEvidenceMapper.class);
            return new JdbcTcStatusPort(new SqlSessionFactoryBuilder().build(config), scope, pool);
        } catch (RuntimeException failure) { pool.close(); throw failure; }
    }

    @Bean
    AllocationRecoverySweep allocationRecoverySweep(SqlSessionFactory sessions, JdbcTcStatusPort port) {
        return new AllocationRecoverySweep(sessions, port, port.scope(), java.time.Clock.systemUTC());
    }
}
