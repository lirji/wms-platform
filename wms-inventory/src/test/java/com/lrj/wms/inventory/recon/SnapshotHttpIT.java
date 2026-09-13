package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.SeedCatalog;
import com.lrj.wms.inventory.masterdata.SeedLocal;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
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
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S9-05：快照 HTTP 导出。测试 JWT，不依赖现场 Casdoor，不开放库账号。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SnapshotHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final Instant RECEIVED = Instant.parse("2026-09-10T13:00:00Z");
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;
    private static org.apache.ibatis.session.SqlSessionFactory sessions;
    private static java.nio.file.Path tokenDirectory;
    private static com.sun.net.httpserver.HttpServer inboundSource;
    private static com.sun.net.httpserver.HttpServer outboundSource;
    private static final java.util.concurrent.atomic.AtomicReference<String> serviceTokenSeen=new java.util.concurrent.atomic.AtomicReference<>();

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(MYSQL.getJdbcUrl(), "UTC"));
            source.setUser(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            Clock clock = Clock.fixed(RECEIVED, ZoneOffset.UTC);
            SeedLocal.seed(source, clock, Set.of(SeedCatalog.WAREHOUSE_A, SeedCatalog.WAREHOUSE_B));
            Configuration config = new Configuration(new Environment("snap-http", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
            config.addMapper(MasterdataMapper.class);
            config.addMapper(InventoryMapper.class);
            config.addMapper(OutboxMapper.class);
            config.addMapper(CommandDedupMapper.class);
            sessions = new SqlSessionFactoryBuilder().build(config);
            try (SqlSession session = sessions.openSession(false)) {
                new InventoryApplicationService(session, clock).receive(SeedCatalog.ENTERPRISE, SeedCatalog.WAREHOUSE_A,
                        "OP-HTTP-SNAP", "DOC", "ACTOR",
                        StockBucketKey.of(SeedCatalog.ENTERPRISE, SeedCatalog.WAREHOUSE_A, SeedCatalog.OWNER,
                                SeedCatalog.WAREHOUSE_A + "-STO", "SKU-STD", MasterdataCodes.NO_LOT,
                                InventoryCodes.QUALITY_GOOD),
                        Quantity.parse("4", 0));
                session.commit();
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            tokenDirectory=java.nio.file.Files.createTempDirectory("reconciliation-http-it-");
            java.nio.file.Files.writeString(tokenDirectory.resolve(com.lrj.wms.runtime.messaging.RuntimeMessage.hash(SeedCatalog.ENTERPRISE)+".jwt"),"fixture.service.jwt");
            inboundSource=sourceFixture("wms-inbound");outboundSource=sourceFixture("wms-outbound");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @LocalServerPort
    private int port;
    @org.springframework.beans.factory.annotation.Autowired
    private ReconciliationCollector collector;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lrj.wms.inventory.jobs.InventoryCatalogJobs jobs;

    @AfterAll
    static void cleanup() {
        MYSQL.stop();
        inboundSource.stop(0);outboundSource.stop(0);
        try {java.nio.file.Files.deleteIfExists(tokenDirectory.resolve(com.lrj.wms.runtime.messaging.RuntimeMessage.hash(SeedCatalog.ENTERPRISE)+".jwt"));java.nio.file.Files.deleteIfExists(tokenDirectory);}
        catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("wms.inventory.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.inventory.datasource.username", MYSQL::getUsername);
        registry.add("wms.inventory.datasource.password", MYSQL::getPassword);
        registry.add("wms.oidc.issuer", () -> ISSUER);
        registry.add("wms.oidc.client-id", () -> "wms-platform");
        registry.add("wms.reconciliation.collector.enabled",() -> true);
        registry.add("wms.reconciliation.collector.inbound-url",() -> "http://127.0.0.1:"+inboundSource.getAddress().getPort());
        registry.add("wms.reconciliation.collector.outbound-url",() -> "http://127.0.0.1:"+outboundSource.getAddress().getPort());
        registry.add("wms.reconciliation.collector.token-directory",() -> tokenDirectory.toString());
        registry.add("wms.reconciliation.collector.allow-http",() -> true);
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
    void exportAndReadQuantitySnapshotThenRejectCrossWarehouse() throws Exception {
        assertEquals(401, post("/api/wms/v1/reconciliation-snapshots", null, "{}").statusCode());
        HttpResponse<String> incomplete = post("/api/wms/v1/reconciliation-snapshots", token(List.of("WH-A")),
                "{\"warehouseIds\":[\"WH-A\"],\"cutoffId\":\"C-HTTP\",\"cutoff\":\"2026-09-12T10:00:00Z\","
                        + "\"sourceWatermark\":\"SRC-1\",\"postingWatermark\":\"POST-1\",\"receiptWatermark\":\"RCV-1\"}");
        assertTrue(incomplete.body().contains("SOURCE_INCOMPLETE"), incomplete.body());
        assertFalse(incomplete.body().contains("\"state\":\"COMPLETE\""), incomplete.body());
        try (SqlSession session = sessions.openSession(false)) {
            VerifiedWindowFixture.seed(session,SeedCatalog.ENTERPRISE,"WH-A","C-HTTP",
                    java.sql.Timestamp.from(Instant.parse("2026-09-12T10:00:00Z")),"SRC-1","POST-1","RCV-1");
            session.commit();
        }
        HttpResponse<String> created = post("/api/wms/v1/reconciliation-snapshots", token(List.of("WH-A")),
                "{\"warehouseIds\":[\"WH-A\"],\"cutoffId\":\"C-HTTP\",\"cutoff\":\"2026-09-12T10:00:00Z\","
                        + "\"sourceWatermark\":\"SRC-1\",\"postingWatermark\":\"POST-1\",\"receiptWatermark\":\"RCV-1\"}");
        assertEquals(202, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"state\":\"COMPLETE\""));
        String snapshotId = extract(created.body(), "snapshotJobId");
        HttpResponse<String> forbidden = get("/api/wms/v1/reconciliation-snapshots/" + snapshotId + "?warehouseId=WH-B",
                token(List.of("WH-A")));
        assertEquals(403, forbidden.statusCode());
        HttpResponse<String> read = get("/api/wms/v1/reconciliation-snapshots/" + snapshotId + "?warehouseId=WH-A",
                token(List.of("WH-A")));
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("quantity"), read.body());
        assertTrue(read.body().contains("EA"), read.body());
        assertTrue(read.body().contains("4"), read.body());
        assertFalse(read.body().contains("currency"), read.body());
        assertFalse(read.body().contains("amountMinor"), read.body());
    }

    /** 来源为HTTP协议夹具，库存鉴权、服务令牌调用、数据库检查点和快照入口均走真实接线。 */
    @Test void trustedWindowRequiresScopeAndOnlyVerifiedServerTokensEnableExport() throws Exception {
        String path="/api/wms/v1/warehouses/WH-B/reconciliation-windows/C-TRUSTED";
        String body="{\"cutoff\":\"2026-09-12T10:00:00Z\"}";
        assertEquals(401,post(path,null,body).statusCode());
        assertEquals(403,post(path,token(List.of("WH-A")),body).statusCode());
        var accepted=post(path,token(List.of("WH-B")),body);
        assertEquals(202,accepted.statusCode(),accepted.body());
        assertTrue(accepted.body().contains("\"watermarksComplete\":false"),accepted.body());
        assertFalse(accepted.body().contains("sourceWatermark"),accepted.body());
        assertEquals(409,post(path,token(List.of("WH-B")),"{\"cutoff\":\"2026-09-12T11:00:00Z\"}").statusCode());
        assertEquals(403,post(path+"/cancellations",token(List.of("WH-B")),"{\"expectedClaimEpoch\":0,\"reason\":\"no remediation scope\"}").statusCode());
        com.xxl.job.core.context.XxlJobContext.setXxlJobContext(new com.xxl.job.core.context.XxlJobContext(
                1,SeedCatalog.ENTERPRISE+",WH-B",1,System.currentTimeMillis(),"",0,1));
        try {for(int n=0;n<7;n++) jobs.stockInternalReconcile();}
        finally {com.xxl.job.core.context.XxlJobContext.setXxlJobContext(null);}
        var verified=get(path,token(List.of("WH-B")));
        assertEquals(200,verified.statusCode(),verified.body());
        assertTrue(verified.body().contains("\"watermarksComplete\":true"),verified.body());
        assertEquals("Bearer fixture.service.jwt",serviceTokenSeen.get());
        assertEquals(403,get(path,token(List.of("WH-A"))).statusCode());
        var request=java.util.Map.of("warehouseIds",List.of("WH-B"),"cutoffId","C-TRUSTED","cutoff","2026-09-12T10:00:00Z",
                "sourceWatermark",extract(verified.body(),"sourceWatermark"),"postingWatermark",extract(verified.body(),"postingWatermark"),"receiptWatermark",extract(verified.body(),"receiptWatermark"));
        var exported=post("/api/wms/v1/reconciliation-snapshots",token(List.of("WH-B")),com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(request));
        assertEquals(202,exported.statusCode(),exported.body());assertTrue(exported.body().contains("\"state\":\"COMPLETE\""),exported.body());
    }

    private static com.sun.net.httpserver.HttpServer sourceFixture(String source) throws java.io.IOException {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            serviceTokenSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String[] path=exchange.getRequestURI().getPath().split("/");String w=path[5],id=path[7];
            boolean page=exchange.getRequestMethod().equals("GET");
            String cutoff=page?java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring("cutoff=".length()),java.nio.charset.StandardCharsets.UTF_8)
                    :com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(exchange.getRequestBody().readAllBytes()).path("cutoff").asString();
            String digest=com.lrj.wms.runtime.messaging.SourceWindowService.initialDigest(source,SeedCatalog.ENTERPRISE,w,id,Instant.parse(cutoff));
            Object body=page?new com.lrj.wms.runtime.messaging.SourceWindowService.Page(1,source,SeedCatalog.ENTERPRISE,w,id,cutoff,0,digest,List.of(),null)
                    :java.util.Map.of("schemaVersion",1,"sourceService",source,"enterpriseId",SeedCatalog.ENTERPRISE,"warehouseId",w,"cutoffId",id,"cutoff",cutoff,"state","COMPLETE","factCount",0,"digest",digest);
            byte[] bytes=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) {out.write(bytes);}
        });server.start();return server;
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void fractionalEpochCannotCancelWindow() throws Exception {
        String path="/api/wms/v1/warehouses/WH-A/reconciliation-windows/C-NUM";
        String bearer=token(List.of("WH-A"),List.of("recon.export","recon.read","recon.remediate"));
        assertEquals(202,post(path,bearer,"{\"cutoff\":\"2026-09-12T10:00:00Z\"}").statusCode());
        assertEquals(400,post(path+"/cancellations",bearer,"{\"expectedClaimEpoch\":0.5,\"reason\":\"fraction\"}").statusCode());
        assertTrue(get(path,bearer).body().contains("\"state\":\"PENDING\""));
        var cancelled=post(path+"/cancellations",bearer,"{\"expectedClaimEpoch\":0,\"reason\":\"stop collection\"}");
        assertEquals(200,cancelled.statusCode(),cancelled.body());assertTrue(cancelled.body().contains("\"state\":\"CANCELLED\""));
    }

    private static String token(List<String> warehouses) throws Exception {
        return token(warehouses,List.of("recon.export","recon.read"));
    }
    private static String token(List<String> warehouses,List<String> scopes) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("wms-recon")
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
