package com.lrj.wms.inbound;

import com.lrj.wms.inbound.protocol.SourceMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptMapper;
import com.lrj.wms.inbound.receipt.InboundTaskMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.DatabaseTimePolicy;
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
@Conditional(OnInboundJdbcConfigured.class)
@EnableConfigurationProperties(InboundDatasourceProperties.class)
class InboundPersistence {
    /** 有界连接池由 Spring 关闭，避免停机留下连接和维护线程。 */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(InboundDatasourceProperties properties, DatabaseBudget budget, DatabaseTimePolicy time) {
        return RuntimeDataSources.create("inbound", properties.url(), properties.username(), properties.password(), budget, time);
    }

    @Bean
    Flyway flyway(DataSource dataSource, DatabaseTimePolicy time) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        time.initialize(dataSource,flyway::migrate);
        return flyway;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, Flyway flyway, DatabaseBudget budget) {
        Configuration config = new Configuration(new Environment("inbound", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(budget.statementTimeoutSeconds());
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.RuntimeInboxMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.MessageRecoveryMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.MessageQueueMetricsMapper.class);
        config.addMapper(SourceMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.SourceOutboxMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.SourceContextMapper.class);
        config.addMapper(InboundReceiptMapper.class);
        config.addMapper(com.lrj.wms.inbound.receipt.ReceiptQualityMapper.class);
        config.addMapper(com.lrj.wms.inbound.receipt.ReceiptSerialPutawayMapper.class);
        config.addMapper(InboundTaskMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
