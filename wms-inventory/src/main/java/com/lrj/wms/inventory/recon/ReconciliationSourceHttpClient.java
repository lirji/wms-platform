package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceWindowService.Page;
import com.lrj.wms.runtime.web.AdmissionBudget;
import com.lrj.wms.runtime.web.AdmissionGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 只调用部署配置的两个来源；令牌由服务凭据提供，不转发用户JWT、不跟随重定向。 */
public final class ReconciliationSourceHttpClient implements ReconciliationSourcePort,AutoCloseable {
    private static final int MAX_BODY=131072;
    private final Map<String,URI> sources;
    private final Function<String,String> tokens;
    private final Duration timeout;
    private final AdmissionGate gate=new AdmissionGate(new AdmissionBudget(8,2,32,8));
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private final AtomicBoolean closed=new AtomicBoolean();

    public ReconciliationSourceHttpClient(Map<String,URI> sources,Function<String,String> tokens,Duration timeout) {
        if(!sources.keySet().equals(Set.of("wms-inbound","wms-outbound")) || timeout.isZero()
                || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(4))>0)
            throw new IllegalArgumentException("对账只允许两个权威来源，单次截止不得超过4秒");
        for(URI uri:sources.values()) {
            if(uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !Set.of("https","http").contains(uri.getScheme()) || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException("来源地址必须是部署配置的HTTP(S)服务根地址");
        }
        this.sources=Map.copyOf(sources);this.tokens=Objects.requireNonNull(tokens);this.timeout=timeout;
        executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r -> {
            var thread=new Thread(r,"reconciliation-source-http");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** 202仅代表来源仍在采集；不能把HTTP成功当作三方对账完整。 */
    @Override public Collection collect(String source,String e,String w,String id,Instant cutoff) {
        var response=request(e,path(source,w,id),RuntimeMessage.JSON.writeValueAsString(Map.of("cutoff",cutoff.toString())));
        var body=decode(response.body());
        if(!body.path("schemaVersion").isIntegralNumber() || !body.path("schemaVersion").canConvertToLong() || body.path("schemaVersion").asLong()!=1
                || !source.equals(body.path("sourceService").asString()) || !cutoff.toString().equals(body.path("cutoff").asString())
                || !String.valueOf(body.path("digest").asString()).matches("[a-f0-9]{64}") || !e.equals(body.path("enterpriseId").asString()) || !w.equals(body.path("warehouseId").asString())
                || !id.equals(body.path("cutoffId").asString()) || !body.path("factCount").isIntegralNumber()
                || !body.path("factCount").canConvertToLong() || body.path("factCount").asLong()<0) throw unavailable();
        String state=body.path("state").asString();
        if(response.statusCode()==200 && !"COMPLETE".equals(state)
                || response.statusCode()==202 && !"COLLECTING".equals(state)) throw unavailable();
        return new Collection(response.statusCode()==200,body.path("factCount").asLong());
    }

    /** 正文限额覆盖chunked；页身份与摘要由持久采集器再次校验，禁止信任调用方字符串。 */
    @Override public Page read(String source,String e,String w,String id,Instant cutoff,String cursor) {
        URI uri=URI.create(path(source,w,id)+"/facts?cutoff="+encode(cutoff.toString())+(cursor==null?"":"&cursor="+encode(cursor)));
        var response=request(e,uri,null);
        if(response.statusCode()!=200) throw unavailable();
        var body=decode(response.body());
        // 映射前拒绝小数、字符串及溢出值，不能依赖JSON库的宽松数值转换。
        if(!body.path("schemaVersion").isIntegralNumber() || !body.path("schemaVersion").canConvertToLong() || body.path("schemaVersion").asLong()!=1
                || !body.path("factCount").isIntegralNumber() || !body.path("factCount").canConvertToLong() || body.path("factCount").asLong()<0
                || !body.path("facts").isArray() || body.path("facts").size()>200) throw unavailable();
        try {return RuntimeMessage.JSON.treeToValue(body,Page.class);}
        catch(RuntimeException malformed) {throw unavailable();}
    }

    private URI path(String source,String w,String id) {
        URI base=sources.get(source);if(base==null) throw new IllegalArgumentException("未知来源服务");
        for(String value:List.of(w,id)) if(value.isBlank() || value.length()>64) throw new IllegalArgumentException("无效关窗范围");
        return base.resolve("/internal/wms/v1/warehouses/"+encode(w)+"/reconciliation-windows/"+encode(id));
    }

    private HttpResponse<byte[]> request(String e,URI uri,String json) {
        if(closed.get()) throw unavailable();
        try(var permit=gate.acquire(e)) {
            if(permit==null) throw unavailable();
            String token=tokens.apply(e);
            if(token==null || token.length()>16384 || !token.matches("[A-Za-z0-9_.-]+")) throw unavailable();
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+token)
                    .header("X-Wms-Enterprise-Id",e).header("Accept","application/json")
                    .header("X-Request-Id",UUID.randomUUID().toString());
            if(json==null) request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
            var future=http.sendAsync(request.build(),info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
            catch(InterruptedException failure) {future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException failure) {future.cancel(true);throw unavailable();}
            if(!Set.of(200,202).contains(response.statusCode()) || !response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) throw unavailable();
            return response;
        } catch(RuntimeException failure) {throw unavailable();}
    }
    private static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static tools.jackson.databind.JsonNode decode(byte[] bytes) {
        try {var body=RuntimeMessage.JSON.readTree(bytes);if(body==null || !body.isObject()) throw unavailable();return body;}
        catch(RuntimeException malformed) {throw unavailable();}
    }
    private static JobRunException unavailable() {return new JobRunException("SOURCE_UNAVAILABLE","来源证明暂不可用或响应不可信");}

    /** 流式累计上限避免来源缺Content-Length时无界接收；超时取消整个请求。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() {return result;}
        @Override public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(chunk.remaining()>MAX_BODY-bytes.size()) {subscription.cancel();result.completeExceptionally(unavailable());return;}
                byte[] copy=new byte[chunk.remaining()];chunk.get(copy);bytes.writeBytes(copy);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {result.completeExceptionally(failure);}
        @Override public void onComplete() {result.complete(bytes.toByteArray());}
    }
    /** 停机最多等待两秒，残余调用保留持久化任务，不能无限挂住进程退出。 */
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        http.shutdown();executor.shutdown();
        try {if(!http.awaitTermination(Duration.ofSeconds(1))) http.shutdownNow();
            if(!executor.awaitTermination(1,TimeUnit.SECONDS)) executor.shutdownNow();}
        catch(InterruptedException failure) {http.shutdownNow();executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
