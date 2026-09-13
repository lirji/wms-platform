package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceWindowService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP协议故障夹具只验证调用边界，不替代来源JAR和库存持久化的完整链路。 */
class ReconciliationSourceHttpClientTest {
    private static final Instant CUTOFF=Instant.parse("2026-09-12T00:00:00Z");
    @Test void preservesScopeAndPendingAndUsesCurrentServiceToken() throws Exception {
        var mode=new AtomicInteger();var tokens=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String digest=SourceWindowService.initialDigest("wms-inbound","E","W","C",CUTOFF);
        server.createContext("/",exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Object body=exchange.getRequestURI().getPath().endsWith("/facts")
                    ? new SourceWindowService.Page(1,"wms-inbound","E","W","C",CUTOFF.toString(),0,digest,List.of(),null)
                    : Map.of("schemaVersion",1,"sourceService","wms-inbound","enterpriseId","E","warehouseId",mode.get()==2?"OTHER":"W",
                            "cutoffId","C","cutoff",CUTOFF.toString(),"factCount",0,"digest",digest,"state",mode.get()==0?"COLLECTING":"COMPLETE");
            if(mode.get()==3) body=Map.of("schemaVersion",4294967297L,"factCount",0,"facts",List.of());
            if(mode.get()==4) body=List.of();
            if(mode.get()==5) body=Map.of("schemaVersion",1,"factCount",0.5,"facts",List.of());
            if(mode.get()==6) body=Map.of("schemaVersion","1","factCount",0,"facts",List.of());
            byte[] bytes=RuntimeMessage.JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(mode.get()==0?202:200,bytes.length);
            try(var out=exchange.getResponseBody()) {out.write(bytes);}
        });server.start();
        var token=new AtomicReference<>("first.service.jwt");
        try(var client=client(server,e -> token.get(),Duration.ofSeconds(2))) {
            assertFalse(client.collect("wms-inbound","E","W","C",CUTOFF).complete());
            mode.set(1);token.set("rotated.service.jwt");
            assertTrue(client.collect("wms-inbound","E","W","C",CUTOFF).complete());
            assertEquals(digest,client.read("wms-inbound","E","W","C",CUTOFF,null).digest());
            assertNotEquals(tokens.getFirst(),tokens.getLast());
            mode.set(2);
            assertThrows(JobRunException.class,() -> client.collect("wms-inbound","E","W","C",CUTOFF));
            assertThrows(IllegalArgumentException.class,() -> client.collect("caller-url","E","W","C",CUTOFF));
            for(int n=3;n<=6;n++) {
                mode.set(n);
                // 每个协议错误使用新调用器，确保断言确实经过解析而非被单企业每秒8次限流提前拒绝。
                try(var malformedClient=client(server,e -> token.get(),Duration.ofSeconds(2))) {
                    assertThrows(JobRunException.class,() -> malformedClient.collect("wms-inbound","E","W","C",CUTOFF));
                    assertThrows(JobRunException.class,() -> malformedClient.read("wms-inbound","E","W","C",CUTOFF,null));
                }
            }
            assertEquals(12,tokens.size());
        } finally {server.stop(0);}
    }

    @Test void oversizedChunkedRedirectAndTimeoutDoNotRetryOrReturnProof() throws Exception {
        var mode=new AtomicInteger();var hits=new AtomicInteger();var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);
        server.createContext("/",exchange -> {
            hits.incrementAndGet();
            try {
                if(mode.get()==2) {release.await(3,TimeUnit.SECONDS);return;}
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(mode.get()==1) {exchange.getResponseHeaders().set("Location","/elsewhere");exchange.sendResponseHeaders(302,-1);return;}
                exchange.sendResponseHeaders(200,0);
                try(var out=exchange.getResponseBody()) {out.write(new byte[140000]);}
            } catch(InterruptedException failure) {Thread.currentThread().interrupt();}
            catch(java.io.IOException cancelled) { /* 客户端拒绝超大正文后主动断开是预期行为。 */ }
            finally {exchange.close();}
        });server.start();
        try(var client=client(server,e -> "service.jwt",Duration.ofMillis(300))) {
            for(int n=0;n<3;n++) {
                mode.set(n);
                assertEquals("SOURCE_UNAVAILABLE",assertThrows(JobRunException.class,
                        () -> client.collect("wms-inbound","E","W","C",CUTOFF)).code());
                assertEquals(n+1,hits.get());
            }
        } finally {release.countDown();server.stop(0);executor.shutdownNow();}
    }
    private static ReconciliationSourceHttpClient client(HttpServer server,java.util.function.Function<String,String> tokens,Duration timeout) {
        URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        return new ReconciliationSourceHttpClient(Map.of("wms-inbound",uri,"wms-outbound",uri),tokens,timeout);
    }
}
