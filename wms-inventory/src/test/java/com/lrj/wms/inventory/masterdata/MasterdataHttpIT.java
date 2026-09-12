package com.lrj.wms.inventory.masterdata;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主数据只读 HTTP：JWT 仓范围生效；不依赖现场 Casdoor。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterdataHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(MYSQL.getJdbcUrl(), "UTC"));
            source.setUser(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            SeedLocal.seed(source, Clock.fixed(Instant.parse("2026-09-10T13:00:00Z"), ZoneOffset.UTC),
                    Set.of(SeedCatalog.WAREHOUSE_A, SeedCatalog.WAREHOUSE_B));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void cleanup() {
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("wms.inventory.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.inventory.datasource.username", MYSQL::getUsername);
        registry.add("wms.inventory.datasource.password", MYSQL::getPassword);
        registry.add("wms.oidc.issuer", () -> ISSUER);
        registry.add("wms.oidc.client-id", () -> "wms-platform");
    }

    @TestConfiguration
    static class JwtOverride {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        assertEquals(401, get("/api/wms/v1/warehouses", null).statusCode());
    }

    @Test
    void warehouseATokenListsOnlyWarehouseA() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses", token(List.of("WH-A")));
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("WH-A"));
        assertTrue(!response.body().contains("WH-B") || warehouseCount(response.body()) == 1);
    }

    @Test
    void crossWarehouseLocationIsForbidden() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses/WH-B/locations", token(List.of("WH-A")));
        assertEquals(403, response.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(response.body(), "code"));
    }

    @Test
    void allowedWarehouseReturnsSeededLocationsAndSkus() throws Exception {
        HttpResponse<String> locations = get("/api/wms/v1/warehouses/WH-A/locations", token(List.of("WH-A")));
        assertEquals(200, locations.statusCode());
        assertTrue(locations.body().contains("WH-A-RCV"));
        HttpResponse<String> skus = get("/api/wms/v1/skus", token(List.of("WH-A")));
        assertEquals(200, skus.statusCode());
        assertTrue(skus.body().contains("SKU-NEAR"));
        assertTrue(skus.body().contains("SKU-EXPIRED"));
    }

    @Test
    void deniedTokenSeesNoWarehousesAndCannotReadLocations() throws Exception {
        String denied = token("wms-denied", List.of());
        HttpResponse<String> warehouses = get("/api/wms/v1/warehouses", denied);
        assertEquals(200, warehouses.statusCode());
        assertTrue(!warehouses.body().contains("WH-A"));
        assertTrue(!warehouses.body().contains("WH-B"));
        HttpResponse<String> locations = get("/api/wms/v1/warehouses/WH-A/locations", denied);
        assertEquals(403, locations.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(locations.body(), "code"));
    }

    @Test
    void opsCsvTokenSeesBothWarehouses() throws Exception {
        String ops = token("wms-ops", "WH-A,WH-B");
        HttpResponse<String> warehouses = get("/api/wms/v1/warehouses", ops);
        assertEquals(200, warehouses.statusCode());
        assertTrue(warehouses.body().contains("WH-A"));
        assertTrue(warehouses.body().contains("WH-B"));
        assertEquals(2, warehouseCount(warehouses.body()));
    }

    @Test
    void warehouseACannotReadWarehouseBLots() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses/WH-B/lots", token(List.of("WH-A")));
        assertEquals(403, response.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(response.body(), "code"));
    }

    @Test
    void warehouseALotsExposeExplicitExpiryInstants() throws Exception {
        HttpResponse<String> lots = get("/api/wms/v1/warehouses/WH-A/lots", token(List.of("WH-A")));
        assertEquals(200, lots.statusCode());
        assertTrue(lots.body().contains("LOT-NEAR"));
        assertTrue(lots.body().contains("LOT-EXP"));
        assertTrue(lots.body().contains("2026-09-17T13:00:00Z"));
        assertTrue(lots.body().contains("2026-09-09T13:00:00Z"));
        assertTrue(lots.body().contains("LOT-STD"));
        assertTrue(!lots.body().contains("2026-09-10T00:00:00Z"));
    }

    @Test
    void skuLotUnitsIncludeCasePackTwelveToOne() throws Exception {
        HttpResponse<String> units = get("/api/wms/v1/skus/SKU-LOT/units", token(List.of("WH-A")));
        assertEquals(200, units.statusCode());
        assertTrue(units.body().contains("CS"));
        assertTrue(units.body().contains("\"numerator\":\"12\""));
        assertTrue(units.body().contains("\"denominator\":\"1\""));
        HttpResponse<String> missing = get("/api/wms/v1/skus/SKU-MISSING/units", token(List.of("WH-A")));
        assertEquals(404, missing.statusCode());
        assertEquals("SKU_NOT_FOUND", extract(missing.body(), "code"));
    }

    @Test
    void writesAndReadsMasterdataById() throws Exception {
        String reader = token(List.of("WH-A"));
        String writer = token("wms-ops", List.of("WH-A", "WH-C"), List.of("masterdata.read", "masterdata.write"));
        HttpResponse<String> denied = post("/api/wms/v1/skus", reader, "KEY-SKU-DENY",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":false,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-DENY\"}");
        assertEquals(403, denied.statusCode());
        assertEquals("SCOPE_FORBIDDEN", extract(denied.body(), "code"));
        HttpResponse<String> createdSku = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":true,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(201, createdSku.statusCode());
        assertTrue(createdSku.body().contains("SKU-HTTP"));
        HttpResponse<String> replaySku = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":true,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(201, replaySku.statusCode());
        HttpResponse<String> mismatch = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-OTHER\",\"name\":\"另一商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":false,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(409, mismatch.statusCode());
        HttpResponse<String> unit = post("/api/wms/v1/skus/SKU-HTTP/units", writer, "KEY-UNIT-HTTP",
                "{\"unitCode\":\"CS\",\"numerator\":\"12\",\"denominator\":\"1\",\"clientOperationId\":\"KEY-UNIT-HTTP\"}");
        assertEquals(201, unit.statusCode());
        HttpResponse<String> warehouse = post("/api/wms/v1/warehouses", writer, "KEY-WH-C",
                "{\"code\":\"WH-C\",\"name\":\"接口新仓\",\"timezone\":\"Asia/Shanghai\",\"clientOperationId\":\"KEY-WH-C\"}");
        assertEquals(201, warehouse.statusCode());
        HttpResponse<String> location = post("/api/wms/v1/warehouses/WH-A/locations", writer, "KEY-LOC-HTTP",
                "{\"code\":\"HTTP\",\"zoneCode\":\"A\",\"locationType\":\"STORAGE\",\"clientOperationId\":\"KEY-LOC-HTTP\"}");
        assertEquals(201, location.statusCode());
        HttpResponse<String> lot = post("/api/wms/v1/warehouses/WH-A/lots", writer, "KEY-LOT-HTTP",
                "{\"ownerId\":\"OWNER-SELF\",\"skuId\":\"SKU-HTTP\",\"lotCode\":\"LOT-HTTP\","
                        + "\"businessLotKey\":\"ENT-DEMO/OWNER-SELF/SKU-HTTP/LOT-HTTP\",\"clientOperationId\":\"KEY-LOT-HTTP\"}");
        assertEquals(201, lot.statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A", reader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/locations/WH-A-RCV", reader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/locations/WH-A-RCV/gate", reader).statusCode());
        HttpResponse<String> sku = get("/api/wms/v1/skus/SKU-HTTP", reader);
        assertEquals(200, sku.statusCode());
        assertTrue(sku.body().contains("CS"));
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/lots/LOT-HTTP", reader).statusCode());
        assertEquals(403, get("/api/wms/v1/warehouses/WH-B", reader).statusCode());
        assertEquals(404, get("/api/wms/v1/skus/SKU-MISSING", reader).statusCode());
        String auditReader = token("wms-ops", List.of("WH-A"), List.of("stock.audit", "operation.read", "task.read"));
        HttpResponse<String> ledger = get("/api/wms/v1/warehouses/WH-A/inventory/BAL-MISSING/ledger", auditReader);
        assertEquals(404, ledger.statusCode());
        assertEquals("BALANCE_NOT_FOUND", extract(ledger.body(), "code"));
        HttpResponse<String> operation = get("/api/wms/v1/operations/OP-MISSING", auditReader);
        assertEquals(404, operation.statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/action-effects", auditReader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/tasks/TASK-MISSING/action-effects", auditReader).statusCode());
    }

    private HttpResponse<String> post(String path, String bearer, String key, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessAndMetricsRequireRealConfigurationAndOperatorScope() throws Exception {
        assertEquals(200, get("/actuator/health/readiness", null).statusCode());
        assertEquals(403, get("/actuator/metrics", token(List.of("WH-A"))).statusCode());
        String operator = token("ops", List.of("WH-A"), List.of("observability.read"));
        assertEquals(200, get("/actuator/metrics", operator).statusCode());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/wms/v1/skus"))
                .header("Authorization", "Bearer " + token(List.of("WH-A")))
                .header("X-Request-Id", "review-r21-request").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("review-r21-request", response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(200, get("/actuator/metrics/http.server.requests", operator).statusCode());
        var missing = get("/api/wms/v1/skus/NO-SUCH-SKU", token(List.of("WH-A")));
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains(missing.headers().firstValue("X-Request-Id").orElseThrow()));
    }

    @org.springframework.beans.factory.annotation.Autowired org.apache.ibatis.session.SqlSessionFactory sessions;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

    @Test void serialRecoveryRequiresWarehouseScopeAndAtomicAuditedRequeue() throws Exception {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var json=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON;
        var now=java.sql.Timestamp.from(Instant.parse("2026-09-12T01:02:03.123456Z"));
        for(int n=1;n<=2;n++) jdbc.update("INSERT INTO serial_recovery_intent(id,enterprise_id,warehouse_id,serial_id,sku_id,operation_id,kind,context_hash,state,claim_epoch,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,? ,?,?,?,'RECEIPT',?,'ISOLATED',7,12,?,?,?)",
                "HTTP-INTENT-"+n,SeedCatalog.ENTERPRISE,"WH-A","HTTP-SN-"+n,"SKU","OP-"+n,"0".repeat(64),now,now,now);
        String path="/api/wms/v1/warehouses/WH-A/serial-recoveries";
        String authorized=token("wms-ops",List.of("WH-A"),List.of("messaging.read","messaging.recover"));
        assertEquals(403,get(path,token(List.of("WH-A"))).statusCode());
        assertEquals(403,get(path,token("wms-ops",List.of("WH-B"),List.of("messaging.read"))).statusCode());
        assertEquals(400,get(path+"?limit=201",authorized).statusCode());
        var first=get(path+"?limit=1&state=ISOLATED",authorized); assertEquals(200,first.statusCode(),first.body());
        var page=json.readTree(first.body()); assertEquals(1,page.path("items").size());
        assertTrue(first.body().contains("2026-09-12T01:02:03.123456Z"));
        assertTrue(!first.body().contains("context_hash"));
        var second=get(path+"?limit=1&state=ISOLATED&cursor="+page.path("nextCursor").asString(),authorized);
        assertEquals(200,second.statusCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(page.path("items").get(0).path("id").asString(),json.readTree(second.body()).path("items").get(0).path("id").asString());
        String retry=path+"/HTTP-INTENT-1/retries";
        String body="{\"expectedEpoch\":7,\"reason\":\"已核对原收货与登记冲突\"}";
        assertEquals(403,postRecovery(retry,token("wms-ops",List.of("WH-A"),List.of("messaging.read")),"DENIED",body).statusCode());
        assertEquals(409,postRecovery(retry,authorized,"STALE",body.replace(":7",":6")).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_audit WHERE intent_id='HTTP-INTENT-1'",Integer.class));
        jdbc.execute("ALTER TABLE serial_recovery_intent ADD CONSTRAINT reject_requeue CHECK (id<>'HTTP-INTENT-1' OR state='ISOLATED')");
        assertEquals(503,postRecovery(retry,authorized,"RETRY-HTTP",body).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_audit WHERE intent_id='HTTP-INTENT-1'",Integer.class));
        jdbc.execute("ALTER TABLE serial_recovery_intent DROP CHECK reject_requeue");
        var accepted=postRecovery(retry,authorized,"RETRY-HTTP",body); assertEquals(202,accepted.statusCode(),accepted.body());
        assertEquals(202,postRecovery(retry,authorized,"RETRY-HTTP",body).statusCode());
        assertEquals(409,postRecovery(retry,authorized,"RETRY-HTTP",body.replace("已核对","换依据")).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_audit WHERE intent_id='HTTP-INTENT-1'",Integer.class));
        assertEquals(8L,jdbc.queryForObject("SELECT claim_epoch FROM serial_recovery_intent WHERE id='HTTP-INTENT-1'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT attempts FROM serial_recovery_intent WHERE id='HTTP-INTENT-1'",Integer.class));
        // 旧领取回执即使迟到，也不能在人工重排后覆盖新代际。
        try(var session=sessions.openSession(false)) {
            assertEquals(0,session.getMapper(com.lrj.wms.inventory.serial.SerialRecoveryMapper.class).finish(SeedCatalog.ENTERPRISE,"WH-A","HTTP-INTENT-1",7,"DONE",null,now,now));
            session.commit();
        }
    }
    @Test void sourceReleaseUsesSameScopedRecoveryListAndAuditedRetry() throws Exception {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO serial_release_intent(id,enterprise_id,warehouse_id,serial_id,sku_id,transfer_id,release_ref,from_epoch,context_hash,state,claim_epoch,attempts,next_attempt_at,created_at,updated_at) VALUES('HTTP-RELEASE',?,'WH-A','HTTP-RELEASE-SN','SKU','TR','REL',4,?,'ISOLATED',9,12,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",SeedCatalog.ENTERPRISE,"a".repeat(64));
        String path="/api/wms/v1/warehouses/WH-A/serial-recoveries";
        String authorized=token("wms-ops",List.of("WH-A"),List.of("messaging.read","messaging.recover"));
        var result=get(path+"?state=ISOLATED",authorized);assertEquals(200,result.statusCode(),result.body());
        assertTrue(result.body().contains("SOURCE_RELEASE"));assertTrue(result.body().contains("HTTP-RELEASE"));
        String body="{\"expectedEpoch\":9,\"reason\":\"已核实原调拨源仓释放流水\"}";
        assertEquals(403,postRecovery(path+"/HTTP-RELEASE/retries",token("wms-ops",List.of("WH-B"),List.of("messaging.recover")),"REL-DENIED",body).statusCode());
        jdbc.execute("ALTER TABLE serial_release_intent ADD CONSTRAINT release_requeue_failure CHECK(id<>'HTTP-RELEASE' OR state='ISOLATED')");
        assertEquals(503,postRecovery(path+"/HTTP-RELEASE/retries",authorized,"REL-RETRY",body).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_audit WHERE intent_id='HTTP-RELEASE'",Integer.class));
        jdbc.execute("ALTER TABLE serial_release_intent DROP CHECK release_requeue_failure");
        assertEquals(202,postRecovery(path+"/HTTP-RELEASE/retries",authorized,"REL-RETRY",body).statusCode());
        assertEquals(202,postRecovery(path+"/HTTP-RELEASE/retries",authorized,"REL-RETRY",body).statusCode());
        assertEquals(10L,jdbc.queryForObject("SELECT claim_epoch FROM serial_release_intent WHERE id='HTTP-RELEASE'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT attempts FROM serial_release_intent WHERE id='HTTP-RELEASE'",Integer.class));
        try(var session=sessions.openSession(false)) {
            var now=java.sql.Timestamp.from(Instant.now());
            assertEquals(0,session.getMapper(com.lrj.wms.inventory.serial.SerialReleaseMapper.class).finish(SeedCatalog.ENTERPRISE,"WH-A","HTTP-RELEASE",9,"DONE",null,now,now));session.commit();
        }
    }
    @Test void countHttpPreservesCompleteSerialInputIncludingEmptySet() throws Exception {
        String e=SeedCatalog.ENTERPRISE,w="WH-COUNT-INPUT",location="LOC-COUNT-INPUT",plan="COUNT-INPUT";String line;
        try(var session=sessions.openSession(false)) {
            var clock=java.time.Clock.systemUTC();var master=new com.lrj.wms.inventory.masterdata.MasterdataService(session,clock);
            master.createWarehouse(w,e,w,"观察测试仓","UTC");master.createLocation(location,"GATE-COUNT-INPUT",e,w,location,"A","STORAGE",new java.math.BigDecimal("100"),"EA");
            master.createSku(com.lrj.wms.inventory.masterdata.domain.SkuPolicy.create("COUNT-SKU",e,"COUNT-SKU","观察商品","EA",0,false,true,false,1,"ACTIVE"),"COUNT-UNIT");
            var bucket=com.lrj.wms.inventory.inventory.domain.StockBucketKey.of(e,w,"OWNER",location,"COUNT-SKU","NO_LOT","HOLD");
            var receipts=new com.lrj.wms.inventory.serial.SerialReceiptService(session,clock,null);
            receipts.stageHold(e,w,"COUNT-RECEIVE-A","DOC","actor","COUNT-A",bucket);receipts.stageHold(e,w,"COUNT-RECEIVE-B","DOC","actor","COUNT-B",bucket);
            var counts=new com.lrj.wms.inventory.count.CountService(session,clock);counts.create(e,w,plan,"CYCLE",List.of(location));counts.startQuiescing(e,w,plan);
            var frozen=counts.freeze(e,w,plan);
            line=String.valueOf(((java.util.List<java.util.Map<String,Object>>)frozen.get("lines")).getFirst().get("id"));session.commit();
        }
        String path="/api/wms/v1/warehouses/"+w+"/count-plans/"+plan+"/observations";
        String authorized=token("wms-ops",List.of(w),List.of("count.record"));
        var json=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON;
        String body=json.writeValueAsString(java.util.Map.of("lineId",line,"qty","1","roundNo",1,"serialObservation",java.util.Map.of("schemaVersion",1,"serialIds",List.of("count-a"))));
        assertEquals(403,postRecovery(path,token("wms-ops",List.of("WH-B"),List.of("count.record")),"COUNT-OBS",body).statusCode());
        var accepted=postRecovery(path,authorized,"COUNT-OBS",body);assertEquals(200,accepted.statusCode(),accepted.body());
        assertEquals(409,postRecovery(path,authorized,"COUNT-OBS",body.replace("count-a","count-b")).statusCode());
        assertEquals(400,postRecovery(path,authorized,"COUNT-BAD-QTY",body.replace("\"1\"","\"2\"")).statusCode());
        String empty=json.writeValueAsString(java.util.Map.of("lineId",line,"qty","0","roundNo",2,"serialObservation",java.util.Map.of("schemaVersion",1,"serialIds",List.of())));
        assertEquals(200,postRecovery(path,authorized,"COUNT-EMPTY",empty).statusCode());
        String noIdentities=json.writeValueAsString(java.util.Map.of("lineId",line,"qty","0","roundNo",2));
        assertEquals(409,postRecovery(path,authorized,"COUNT-EMPTY",noIdentities).statusCode());
        assertEquals(200,postRecovery(path,authorized,"COUNT-OBS",body).statusCode());
        assertEquals(400,postRecovery(path,authorized,"COUNT-NO-IDENTITIES",noIdentities.replace(":2",":3")).statusCode());
        assertEquals(400,postRecovery(path,authorized,"COUNT-BAD-VERSION",body.replace("\"schemaVersion\":1","\"schemaVersion\":1.5")).statusCode());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertEquals(0,jdbc.queryForObject("SELECT counted_qty FROM count_line WHERE id=?",java.math.BigDecimal.class,line).signum());
        assertEquals("wms-ops",jdbc.queryForObject("SELECT actor_id FROM count_observation WHERE warehouse_id=? AND observation_id='COUNT-EMPTY'",String.class,w));
    }
    private HttpResponse<String> postRecovery(String path,String bearer,String command,String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Authorization","Bearer "+bearer).header("Idempotency-Key",command).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> warehouses) throws Exception {
        return token("wms-wh-a", warehouses);
    }

    private static String token(String subject, List<String> warehouses) throws Exception {
        return signed(subject, warehouses, List.of("masterdata.read"));
    }

    private static String token(String subject, List<String> warehouses, List<String> scopes) throws Exception {
        return signed(subject, warehouses, scopes);
    }

    private static String token(String subject, String warehousesCsv) throws Exception {
        return signed(subject, warehousesCsv, List.of("masterdata.read"));
    }

    private static String signed(String subject, Object warehouses, List<String> scopes) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE)
                .claim("warehouses", warehouses)
                .claim("scope", scopes)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    private static int warehouseCount(String body) {
        int count = 0;
        if (body.contains("\"id\":\"WH-A\"") || body.contains("WH-A")) {
            count++;
        }
        if (body.contains("WH-B")) {
            count++;
        }
        return count;
    }

    private static String extract(String body, String field) {
        String needle = "\"" + field + "\":\"";
        int start = body.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        return body.substring(from, body.indexOf('"', from));
    }
}
