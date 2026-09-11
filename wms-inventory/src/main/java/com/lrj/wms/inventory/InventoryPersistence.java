package com.lrj.wms.inventory;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅在显式配置非空 JDBC 时接库并迁移；smoke 无 URL 时不启动数据源。 */
@org.springframework.context.annotation.Configuration
@Conditional(OnInventoryJdbcConfigured.class)
@EnableConfigurationProperties(InventoryDatasourceProperties.class)
class InventoryPersistence {
    @Bean
    DataSource dataSource(InventoryDatasourceProperties properties) {
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
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(com.lrj.wms.inventory.effect.infrastructure.EffectMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper.class);
        config.addMapper(com.lrj.wms.inventory.quality.QualityQualificationMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.FefoCandidateMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
