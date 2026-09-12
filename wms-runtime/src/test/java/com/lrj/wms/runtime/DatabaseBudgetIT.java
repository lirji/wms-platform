package com.lrj.wms.runtime;

import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL 验证连接耗尽及时拒绝、释放后恢复以及 SQL 超时。 */
class DatabaseBudgetIT {
    @Test void boundsConnectionsAndRecoversWithoutLeaking() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            var budget = new DatabaseBudget(1, 0, 300, 250, 1, 1000, 3000);
            try (var pool = RuntimeDataSources.create("budget-it", mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), budget)) {
                var environment = new org.springframework.mock.env.MockEnvironment()
                        .withProperty("wms.oidc.issuer", "https://issuer.test")
                        .withProperty("wms.oidc.client-id", "wms-platform");
                var readiness = new com.lrj.wms.runtime.observability.RuntimeReadiness(() -> pool, environment);
                assertEquals("UP", readiness.health().getStatus().getCode());
                try (var connection = pool.getConnection()) {
                    assertEquals("DOWN", readiness.health().getStatus().getCode());
                    assertThrows(SQLException.class, pool::getConnection);
                    assertEquals(1, pool.getHikariPoolMXBean().getTotalConnections());
                }
                try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                    statement.setQueryTimeout(budget.statementTimeoutSeconds());
                    assertThrows(SQLException.class, () -> statement.executeQuery("SELECT SLEEP(4)"));
                }
                assertEquals("UP", readiness.health().getStatus().getCode());
                try (var connection = pool.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
