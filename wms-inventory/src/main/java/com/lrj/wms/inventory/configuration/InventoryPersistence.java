package com.lrj.wms.inventory.configuration;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.tcc.application.InventoryTccFence;
import com.lrj.wms.inventory.tcc.seata.ReservationTccAction;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.DatabaseTimePolicy;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import javax.sql.DataSource;

/** 仅在显式配置非空 JDBC 时接库并迁移；smoke 无 URL 时不启动数据源。 */
@org.springframework.context.annotation.Configuration
@Conditional(OnInventoryJdbcConfigured.class)
@EnableConfigurationProperties({
    InventoryDatasourceProperties.class,
    com.lrj.wms.inventory.inventory.outbox.OutboxBudget.class
})
class InventoryPersistence {
    /** 有界连接池由 Spring 关闭，避免停机留下连接和维护线程。 */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(
            InventoryDatasourceProperties properties,
            DatabaseBudget budget,
            DatabaseTimePolicy time) {
        return RuntimeDataSources.create(
                "inventory",
                properties.url(),
                properties.username(),
                properties.password(),
                budget,
                time);
    }

    @Bean
    Flyway flyway(DataSource dataSource, DatabaseTimePolicy time) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load();
        time.initialize(dataSource, flyway::migrate);
        return flyway;
    }

    /** HTTP/任务使用显式 SqlSession 提交；必须由 JDBC 会话掌管物理事务。 */
    @Bean
    SqlSessionFactory sqlSessionFactory(
            DataSource dataSource, Flyway flyway, DatabaseBudget budget) {
        return sessions(dataSource, new JdbcTransactionFactory(), budget);
    }

    static SqlSessionFactory sessions(
            DataSource dataSource, TransactionFactory transactions, DatabaseBudget budget) {
        Configuration config =
                new Configuration(new Environment("inventory", transactions, dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(budget.statementTimeoutSeconds());
        config.addMapper(com.lrj.wms.runtime.messaging.inbox.persistence.RuntimeInboxMapper.class);
        config.addMapper(
                com.lrj.wms.runtime.messaging.recovery.persistence.MessageRecoveryMapper.class);
        config.addMapper(
                com.lrj.wms.runtime.messaging.observability.persistence.MessageQueueMetricsMapper
                        .class);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(com.lrj.wms.inventory.tcc.persistence.RuntimeTccMapper.class);
        config.addMapper(
                com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper.class);
        config.addMapper(com.lrj.wms.inventory.effect.infrastructure.EffectMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.outbox.persistence.OutboxMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper.class);
        config.addMapper(
                com.lrj.wms.inventory.quality.persistence.QualityQualificationMapper.class);
        config.addMapper(com.lrj.wms.inventory.quality.persistence.ReceiptQualityStockMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.LocalSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.SerialReceiptBatchMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.SerialRecoveryMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.SerialReleaseMapper.class);
        config.addMapper(
                com.lrj.wms.inventory.serial.persistence.SerialTransferCommandMapper.class);
        config.addMapper(com.lrj.wms.inventory.count.persistence.CountMapper.class);
        config.addMapper(com.lrj.wms.inventory.count.persistence.CountSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.SerialOutboundMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.persistence.SerialShipmentMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.FefoCandidateMapper.class);
        config.addMapper(com.lrj.wms.inventory.jobs.persistence.JobRunMapper.class);
        config.addMapper(
                com.lrj.wms.inventory.jobs.expiry.persistence.ExpiryEligibilityMapper.class);
        config.addMapper(com.lrj.wms.inventory.query.persistence.ProjectionMapper.class);
        config.addMapper(com.lrj.wms.inventory.query.persistence.InventoryHttpQueryMapper.class);
        config.addMapper(com.lrj.wms.inventory.operations.persistence.DomainCommandMapper.class);
        config.addMapper(com.lrj.wms.inventory.recon.persistence.ReconciliationMapper.class);
        config.addMapper(
                com.lrj.wms.inventory.recon.collection.persistence.ReconciliationCollectionMapper
                        .class);
        config.addMapper(com.lrj.wms.inventory.archive.persistence.ArchivePlanMapper.class);
        config.addMapper(com.lrj.wms.inventory.recon.snapshot.persistence.SnapshotMapper.class);
        config.addMapper(com.lrj.wms.inventory.migrate.persistence.WarehouseRouteMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** Fence 与库存余额共用物理库；绑定失败直接阻止启动，不回落 AT。 */
    @Bean
    SpringFenceHandler inventoryTccFence(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return InventoryTccFence.bind(dataSource, transactionManager);
    }

    /** TCC 经官方 Fence 的 Spring 事务；模板与 Fence 共享数据源，不能复用手动事务工厂。 */
    @Bean
    ReservationTccAction reservationTccAction(
            DataSource dataSource,
            Flyway flyway,
            DatabaseBudget budget,
            SpringFenceHandler inventoryTccFence) {
        SqlSessionFactory sqlSessionFactory =
                sessions(dataSource, new SpringManagedTransactionFactory(), budget);
        return new ReservationTccAction(
                new com.lrj.wms.inventory.inventory.application.InventoryApplicationService(
                        new SqlSessionTemplate(sqlSessionFactory), Clock.systemUTC()));
    }
}
