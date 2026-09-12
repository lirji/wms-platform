package com.lrj.wms.inbound;

import com.lrj.wms.inbound.protocol.SourceMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
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
    @Bean
    DataSource dataSource(InboundDatasourceProperties properties) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(properties.url());
        source.setUser(properties.username());
        source.setPassword(properties.password());
        return source;
    }

    @Bean
    Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, Flyway flyway) {
        Configuration config = new Configuration(new Environment("inbound", new JdbcTransactionFactory(), dataSource));
        config.addMapper(SourceMapper.class);
        config.addMapper(InboundReceiptMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
