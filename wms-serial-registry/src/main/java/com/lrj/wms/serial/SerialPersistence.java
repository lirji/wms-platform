package com.lrj.wms.serial;

import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import com.zaxxer.hikari.HikariDataSource;
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

/** 登记权威数据使用独立关系库；不连接inventory分库，不跨库加入库存事务。 */
@org.springframework.context.annotation.Configuration
@Conditional(OnSerialJdbcConfigured.class)
@EnableConfigurationProperties({SerialDatasourceProperties.class, SerialAccessProperties.class})
public class SerialPersistence {
    /** 连接预算沿用公共运行约束；没有受信调用主体时拒绝启动业务库。 */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(SerialDatasourceProperties properties, SerialAccessProperties access, DatabaseBudget budget) {
        if (access.allowedSubjects().isEmpty()) throw new IllegalArgumentException("必须显式配置序列号登记受信服务主体");
        return RuntimeDataSources.create("serial-registry", properties.url(), properties.username(), properties.password(), budget);
    }

    @Bean
    Flyway flyway(DataSource source) {
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration/registry").load();
        migration.migrate();
        return migration;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource source, Flyway migration, DatabaseBudget budget) {
        var config = new Configuration(new Environment("serial-registry", new JdbcTransactionFactory(), source));
        config.setDefaultStatementTimeout(budget.statementTimeoutSeconds());
        config.addMapper(SerialRegistryMapper.class);
        config.addMapper(SerialTransferMapper.class);
        config.addMapper(SerialHttpCommandMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

}
