package com.lrj.wms.inventory.serial;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真HTTP故障服务器验证传输边界；业务事务另由MySQL/登记进程集成测试证明。 */
class SerialRegistryHttpClientTest {
    @TempDir Path secrets;
    @Test void originalKeySurvivesTokenRotationAndUntrustedSuccessCannotAuthorize() throws Exception {
        var keys=new ArrayList<String>(); var tokens=new ArrayList<String>();
        var invalid=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body=RuntimeMessage.JSON.writeValueAsBytes(Map.of("normalizedSerial","SN-I","state","ACTIVE",
                    "ownerEpoch",1,"ownerWarehouseId",invalid.get()?"OTHER":"WH","claimOperationId","OP","receiptOperationId","OP"));
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        Path tokenFile=secrets.resolve(SerialRegistryHttpClient.digest("ENT")+".jwt"); Files.writeString(tokenFile,"old.service.jwt");
        try(var client=new SerialRegistryHttpClient(url(server),e -> SerialRegistryClientConfiguration.readToken(secrets,e),Duration.ofMillis(500))) {
            assertEquals("ACTIVE",client.activate("ENT","SKU","sn-i","WH","OP").get("state"));
            Files.writeString(tokenFile,"new.service.jwt");
            client.activate("ENT","SKU","SN-I","WH","OP");
            assertEquals(keys.getFirst(),keys.getLast()); assertNotEquals(tokens.getFirst(),tokens.getLast());
            invalid.set(true);
            assertThrows(SerialRegistryUnavailableException.class,() -> client.activate("ENT","SKU","SN-I","WH","OP"));
            assertThrows(SerialRegistryUnavailableException.class,() -> client.get("UNKNOWN","SKU","SN-I"));
            assertEquals(3,keys.size());
        } finally { server.stop(0); }
    }
    @Test void chunkedOversizeRedirectAndTimeoutFailClosedWithoutRetries() throws Exception {
        var mode=new AtomicInteger(); var hits=new AtomicInteger(); var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newFixedThreadPool(2); server.setExecutor(executor);
        server.createContext("/",exchange -> {
            hits.incrementAndGet(); int scenario=mode.get();
            try {
                if(scenario==2) { release.await(3,TimeUnit.SECONDS); return; }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(scenario==1) { exchange.getResponseHeaders().set("Location","/redirect"); exchange.sendResponseHeaders(302,-1); return; }
                exchange.sendResponseHeaders(200,0);
                try(var output=exchange.getResponseBody()) { output.write("x".repeat(70000).getBytes(StandardCharsets.US_ASCII)); }
            } catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch(java.io.IOException cancelled) { /* 有界客户端关闭超大响应属于预期。 */ }
            finally { exchange.close(); }
        }); server.start();
        try(var client=new SerialRegistryHttpClient(url(server),e -> "service.jwt.token",Duration.ofMillis(150))) {
            for(int n=0;n<3;n++) {
                mode.set(n); int before=hits.get(); long started=System.nanoTime();
                assertThrows(SerialRegistryUnavailableException.class,() -> client.get("ENT","SKU","SN"));
                assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(2))<0);
                assertEquals(before+1,hits.get());
            }
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
    @Test void tenantConcurrencyRejectsBeforeNetworkWhileAnotherTenantCanProceed() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1); var hits=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var worker=Executors.newFixedThreadPool(4); server.setExecutor(worker);
        server.createContext("/",exchange -> {
            hits.incrementAndGet(); entered.countDown();
            try { release.await(3,TimeUnit.SECONDS); exchange.sendResponseHeaders(503,-1); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        var callers=Executors.newFixedThreadPool(2);
        try(var client=new SerialRegistryHttpClient(url(server),e -> "service.jwt.token",Duration.ofMillis(1500))) {
            var first=callers.submit(() -> assertThrows(SerialRegistryUnavailableException.class,() -> client.get("ENT","SKU","1")));
            var second=callers.submit(() -> assertThrows(SerialRegistryUnavailableException.class,() -> client.get("ENT","SKU","2")));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertThrows(SerialRegistryUnavailableException.class,() -> client.get("ENT","SKU","3")); assertEquals(2,hits.get());
            release.countDown();
            assertThrows(SerialRegistryUnavailableException.class,() -> client.get("OTHER","SKU","4")); assertEquals(3,hits.get());
            first.get(2,TimeUnit.SECONDS); second.get(2,TimeUnit.SECONDS);
        } finally { release.countDown(); server.stop(0); callers.shutdownNow(); worker.shutdownNow(); }
    }
    private static URI url(HttpServer server) { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
}
