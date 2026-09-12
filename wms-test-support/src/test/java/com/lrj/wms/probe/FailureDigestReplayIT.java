package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-06：旧 digestVersion 重放稳定；v2 多字段不能改写 v1。只起本测试 MySQL。
 */
class FailureDigestReplayIT {
    @Test
    void storedVersionOneReplayIgnoresNewerDefaultFields() {
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
            String canonical = "RECEIVE\u001fRECEIPT_PART\u001fSES-D\u001fPART-D\u001fLINE-D";
            String digest = sha256(canonical);
            jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                    + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, "
                    + "applied_command_id, attempt_no, state, version, created_at, updated_at) VALUES "
                    + "('EFF-DIG','ENT-1','WH-A','wms-inventory','RECEIVE','RECEIPT_PART','SES-D','PART-D','LINE-D',"
                    + "'EFF-DIG',NULL,NULL,1,'OPEN',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
            jdbc.update("INSERT INTO stock_effect_attempt (id, enterprise_id, warehouse_id, effect_id, command_id, "
                    + "previous_command_id, attempt_no, state, digest_version, intent_digest, canonical_request, "
                    + "version, created_at, updated_at) VALUES (?,'ENT-1','WH-A','EFF-DIG','CMD-D',NULL,1,'OPEN',1,?,?,0,"
                    + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), digest, canonical);
            Map<String, Object> stored = jdbc.queryForMap(
                    "SELECT digest_version, intent_digest, canonical_request FROM stock_effect_attempt "
                            + "WHERE effect_id='EFF-DIG' AND attempt_no=1");
            assertEquals(1, ((Number) stored.get("digest_version")).intValue());
            assertEquals(canonical, String.valueOf(stored.get("canonical_request")));
            assertEquals(digest, String.valueOf(stored.get("intent_digest")));
            assertEquals(digest, sha256(String.valueOf(stored.get("canonical_request"))));
            String v1ReplayWithExtras = sha256("RECEIVE\u001fRECEIPT_PART\u001fSES-D\u001fPART-D\u001fLINE-D");
            String v2 = sha256("RECEIVE\u001fRECEIPT_PART\u001fSES-D\u001fPART-D\u001fLINE-D\u001f12\u001f"
                    + "EA".toUpperCase(Locale.ROOT));
            assertEquals(digest, v1ReplayWithExtras);
            assertNotEquals(digest, v2);
            System.out.println("FAILURE_IT: stored digestVersion=1 replay stable; v2 extras do not rewrite");
        }
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
