package com.lrj.wms.outbound;

import com.lrj.wms.outbound.order.OutboundAuthorizationMapper;
import com.lrj.wms.outbound.order.OutboundOrderMapper;
import com.lrj.wms.outbound.order.OutboundTaskMapper;
import com.lrj.wms.outbound.protocol.SourceMapper;
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
@Conditional(OnOutboundJdbcConfigured.class)
@EnableConfigurationProperties(OutboundDatasourceProperties.class)
class OutboundPersistence {
    @Bean
    DataSource dataSource(OutboundDatasourceProperties properties) {
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
        Configuration config = new Configuration(new Environment("outbound", new JdbcTransactionFactory(), dataSource));
        config.addMapper(SourceMapper.class);
        config.addMapper(OutboundOrderMapper.class);
        config.addMapper(OutboundTaskMapper.class);
        config.addMapper(OutboundAuthorizationMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
