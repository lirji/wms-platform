package com.lrj.wms.inventory;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.tcc.InventoryTccFence;
import com.lrj.wms.inventory.tcc.ReservationTccAction;
import com.zaxxer.hikari.HikariDataSource;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅在显式配置非空 JDBC 时接库并迁移；smoke 无 URL 时不启动数据源。 */
@org.springframework.context.annotation.Configuration
@Conditional(OnInventoryJdbcConfigured.class)
@EnableConfigurationProperties({InventoryDatasourceProperties.class, com.lrj.wms.inventory.inventory.OutboxBudget.class})
class InventoryPersistence {
    /** 有界连接池由 Spring 关闭，避免停机留下连接和维护线程。 */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(InventoryDatasourceProperties properties, DatabaseBudget budget) {
        return RuntimeDataSources.create("inventory", properties.url(), properties.username(), properties.password(), budget);
    }

    @Bean
    Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, Flyway flyway, DatabaseBudget budget) {
        Configuration config = new Configuration(
                new Environment("inventory", new SpringManagedTransactionFactory(), dataSource));
        config.setDefaultStatementTimeout(budget.statementTimeoutSeconds());
        config.addMapper(MasterdataMapper.class);
        config.addMapper(com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper.class);
        config.addMapper(com.lrj.wms.inventory.effect.infrastructure.EffectMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper.class);
        config.addMapper(com.lrj.wms.inventory.quality.QualityQualificationMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.count.CountMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.FefoCandidateMapper.class);
        config.addMapper(com.lrj.wms.inventory.jobs.JobRunMapper.class);
        config.addMapper(com.lrj.wms.inventory.jobs.ExpiryEligibilityMapper.class);
        config.addMapper(com.lrj.wms.inventory.query.ProjectionMapper.class);
        config.addMapper(com.lrj.wms.inventory.query.InventoryHttpQueryMapper.class);
        config.addMapper(com.lrj.wms.inventory.domain.infrastructure.DomainCommandMapper.class);
        config.addMapper(com.lrj.wms.inventory.recon.ReconciliationMapper.class);
        config.addMapper(com.lrj.wms.inventory.recon.SnapshotMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** Fence 与库存余额共用物理库；绑定失败直接阻止启动，不回落 AT。 */
    @Bean
    SpringFenceHandler inventoryTccFence(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return InventoryTccFence.bind(dataSource, transactionManager);
    }

    @Bean
    ReservationTccAction reservationTccAction(SqlSessionFactory sqlSessionFactory) {
        return new ReservationTccAction(
                new com.lrj.wms.inventory.inventory.InventoryApplicationService(new SqlSessionTemplate(sqlSessionFactory),
                        Clock.systemUTC()));
    }
}
