package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-06：迁移后效果/尝试身份仍唯一。只起本测试 MySQL，不杀共享 dev-infra。
 */
class FailureIdentityUniquenessIT {
    @Test
    void migratedIdentityKeysRejectDuplicates() {
        assertTrue(DockerClientFactory.instance().isDockerAvailable(), "failure-it 需要 Docker，禁止 skip");
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            var source = new MysqlDataSource();
            source.setUrl(mysql.getJdbcUrl());
            source.setUser(mysql.getUsername());
            source.setPassword(mysql.getPassword());
            InventoryMigrationSupport.migrate(source);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                    + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, "
                    + "applied_command_id, attempt_no, state, version, created_at, updated_at) VALUES "
                    + "('EFF-1','ENT-1','WH-A','wms-inventory','RECEIVE','RECEIPT_PART','SES','PART','LINE','EFF-1',"
                    + "NULL,NULL,1,'OPEN',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
            assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                            + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, "
                            + "applied_command_id, attempt_no, state, version, created_at, updated_at) VALUES "
                            + "('EFF-2','ENT-1','WH-A','wms-inventory','RECEIVE','RECEIPT_PART','SES','PART','LINE',"
                            + "'EFF-2',NULL,NULL,0,'REGISTERED',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))"));
            jdbc.update("INSERT INTO stock_effect_attempt (id, enterprise_id, warehouse_id, effect_id, command_id, "
                    + "previous_command_id, attempt_no, state, digest_version, intent_digest, canonical_request, "
                    + "version, created_at, updated_at) VALUES ('ATT-1','ENT-1','WH-A','EFF-1','CMD-1',NULL,1,'OPEN',1,"
                    + "'" + "a".repeat(64) + "','RECEIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
            assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "INSERT INTO stock_effect_attempt (id, enterprise_id, warehouse_id, effect_id, command_id, "
                            + "previous_command_id, attempt_no, state, digest_version, intent_digest, canonical_request, "
                            + "version, created_at, updated_at) VALUES ('ATT-2','ENT-1','WH-A','EFF-1','CMD-2',NULL,1,"
                            + "'OPEN',1,'" + "b".repeat(64) + "','RECEIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))"));
            System.out.println("FAILURE_IT: migrated effect/attempt unique keys still reject duplicates");
        }
    }
}
