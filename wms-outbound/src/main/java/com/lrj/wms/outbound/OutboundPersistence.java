package com.lrj.wms.outbound;

import com.lrj.wms.outbound.order.OutboundAuthorizationMapper;
import com.lrj.wms.outbound.order.OutboundOrderMapper;
import com.lrj.wms.outbound.order.OutboundTaskMapper;
import com.lrj.wms.outbound.protocol.SourceMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/** 仅在显式配置非空 JDBC 时接库并迁移。 */
@org.springframework.context.annotation.Configuration
@Conditional(OnOutboundJdbcConfigured.class)
@EnableConfigurationProperties(OutboundDatasourceProperties.class)
class OutboundPersistence {
    /** 有界连接池由 Spring 关闭，避免停机留下连接和维护线程。 */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(OutboundDatasourceProperties properties, DatabaseBudget budget) {
        return RuntimeDataSources.create("outbound", properties.url(), properties.username(), properties.password(), budget);
    }

    @Bean
    Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, Flyway flyway, DatabaseBudget budget) {
        Configuration config = new Configuration(new Environment("outbound", new JdbcTransactionFactory(), dataSource));
        config.setDefaultStatementTimeout(budget.statementTimeoutSeconds());
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.RuntimeInboxMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.MessageQueueMetricsMapper.class);
        config.addMapper(SourceMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.SourceOutboxMapper.class);
        config.addMapper(OutboundOrderMapper.class);
        config.addMapper(OutboundTaskMapper.class);
        config.addMapper(OutboundAuthorizationMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
