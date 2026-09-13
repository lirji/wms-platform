package com.lrj.wms.inventory.serial;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.web.AdmissionBudget;
import com.lrj.wms.runtime.web.AdmissionGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 登记调用只传受控服务令牌，超时结果未知时交给持久化任务以同一动作身份恢复。 */
public final class SerialRegistryHttpClient implements SerialRegistryPort, SerialCountRegistryPort, SerialTransferRegistryPort, SerialReleaseRegistryPort, SerialShipmentRegistryPort, AutoCloseable {
    private static final int MAX_BODY = 65536;
    private final URI base;
    private final Function<String,String> tokens;
    private final Duration timeout;
    private final AdmissionGate gate = new AdmissionGate(new AdmissionBudget(8,2,32,8));
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SerialRegistryHttpClient(URI base, Function<String,String> tokens, Duration timeout) {
        if (base.getHost()==null || base.getRawUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMillis(1500))>0)
            throw new IllegalArgumentException("登记地址须为HTTP(S)服务根地址，调用截止时间不超过1500ms");
        this.base=base.resolve("/internal/wms/v1/serial-identities"); this.tokens=tokens; this.timeout=timeout;
        executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r -> {
            Thread thread=new Thread(r,"serial-registry-http"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** 认领不能把其他操作或转移中的身份当作当前收货的有效凭证。 */
    @Override public Map<String,Object> claim(String e,String sku,String serial,String wh,String op) {
        return identity("claims",e,sku,serial,wh,op,Set.of("CLAIMED","ACTIVE"));
    }
    @Override public Map<String,Object> activate(String e,String sku,String serial,String wh,String op) {
        return identity("activations",e,sku,serial,wh,op,Set.of("ACTIVE"));
    }
    @Override public Map<String,Object> claimFound(String e,String sku,String serial,String wh,String op) {
        return identity("found-claims",e,sku,serial,wh,op,Set.of("CLAIMED","FOUND_CLAIMED","ACTIVE"));
    }
    @Override public Map<String,Object> activateFound(String e,String sku,String serial,String wh,String op) {
        return identity("found-activations",e,sku,serial,wh,op,Set.of("ACTIVE"));
    }
    @Override public Map<String,Object> markMissing(String e,String sku,String serial,String wh,String fact,long epoch) {
        var result=send("missing",e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"factRef",fact,"expectedEpoch",epoch));
        validate(result,serial,Set.of("MISSING")); require(result,"ownerWarehouseId",wh); require(result,"receiptOperationId",fact);
        if (((Number)result.get("ownerEpoch")).longValue()!=epoch) throw unavailable();
        return result;
    }
    /** 同一原发运事实在断连或令牌轮换后仍使用稳定命令键。 */
    @Override public Map<String,Object> ship(String e,String sku,String serial,String wh,String ref,long epoch) {
        var result=send("shipments",e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"factRef",ref,"expectedEpoch",epoch));
        SerialShipmentRecoveryService.requireProof(result,e,wh,sku,normalize(serial),ref,epoch);return result;
    }
    @Override public Map<String,Object> get(String e,String sku,String serial) {
        var result=request(e,URI.create(base+"?skuId="+encode(sku)+"&serial="+encode(normalize(serial))),null,null);
        validate(result,serial,Set.of("CLAIMED","ACTIVE","MISSING","FOUND_CLAIMED","TRANSFER_PREPARED","IN_TRANSIT","RECEIVING","SHIPPED"));
        return result;
    }
    /** 原准备命令回执可重放，固定源目的仓、转移及epoch，不从后来归属重建意图。 */
    @Override public Map<String,Object> prepare(String e,String sku,String serial,String transfer,String wh,String target,String operation,long epoch) {
        var result=send("transfer-preparations",e,Map.of("warehouseId",wh,"targetWarehouseId",target,"skuId",sku,
                "serial",normalize(serial),"transferId",transfer,"operationId",operation,"expectedEpoch",epoch));
        // 准备是历史事实，不要求当前身份仍停留在TRANSFER_PREPARED，也不由此授予当前可用库存。
        if(!(result.get("transferPreparation") instanceof Map<?,?> proof) || !e.equals(proof.get("enterpriseId")) || !sku.equals(proof.get("skuId"))
                || !normalize(serial).equals(proof.get("normalizedSerial")) || !transfer.equals(proof.get("transferId")) || !wh.equals(proof.get("sourceWarehouseId"))
                || !target.equals(proof.get("targetWarehouseId")) || !operation.equals(proof.get("prepareOperationId"))
                || !(proof.get("schemaVersion") instanceof Number version) || !(version instanceof Integer || version instanceof Long) || version.longValue()!=1
                || !(proof.get("fromEpoch") instanceof Number from) || !(from instanceof Integer || from instanceof Long) || from.longValue()!=epoch) throw unavailable();
        return result;
    }
    /** 源仓事实以稳定命令键重放，核对历史凭证而非当前授权状态。 */
    @Override public Map<String,Object> release(String e,String sku,String serial,String transfer,String wh,String ref,long epoch) {
        var result=send("source-releases",e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"transferId",transfer,"factRef",ref,"expectedEpoch",epoch));
        SerialReleaseRecoveryService.requireProof(result,e,wh,sku,normalize(serial),transfer,ref,epoch);return result;
    }
    @Override public Map<String,Object> startReceiving(String e,String sku,String serial,String transfer,String wh,String ref,long epoch) {
        var result=send("destination-receivings",e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"transferId",transfer,"factRef",ref,"expectedEpoch",epoch));
        validate(result,serial,Set.of("RECEIVING","ACTIVE")); require(result,"transferId",transfer);
        long observed=((Number)result.get("ownerEpoch")).longValue();
        if ("ACTIVE".equals(result.get("state"))) {
            require(result,"ownerWarehouseId",wh); require(result,"receiptOperationId",ref);
            if(observed!=epoch+1) throw unavailable();
        } else if(observed!=epoch) throw unavailable();
        return result;
    }
    @Override public Map<String,Object> confirmDestination(String e,String sku,String serial,String transfer,String wh,String ref) {
        var result=send("destination-confirmations",e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"transferId",transfer,"factRef",ref));
        validate(result,serial,Set.of("ACTIVE")); require(result,"transferId",transfer); require(result,"ownerWarehouseId",wh);
        require(result,"receiptOperationId",ref); return result;
    }

    private Map<String,Object> identity(String action,String e,String sku,String serial,String wh,String op,Set<String> states) {
        var result=send(action,e,Map.of("warehouseId",wh,"skuId",sku,"serial",normalize(serial),"operationId",op));
        validate(result,serial,states); require(result,"ownerWarehouseId",wh); require(result,"claimOperationId",op);
        if ("ACTIVE".equals(result.get("state"))) require(result,"receiptOperationId",op);
        return result;
    }
    private Map<String,Object> send(String action,String enterprise,Map<String,Object> body) {
        String json=RuntimeMessage.JSON.writeValueAsString(new TreeMap<>(body));
        // 键不含令牌或进程身份，令牌轮转、重启和丢回执仍指向同一登记命令。
        String key=digest(enterprise+"\n"+action+"\n"+json);
        return request(enterprise,URI.create(base+"/"+action),json,key);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> request(String enterprise,URI uri,String json,String key) {
        if(closed.get()) throw unavailable();
        try(var permit=gate.acquire(enterprise)) {
            if(permit==null) throw unavailable();
            String token=tokens.apply(enterprise);
            if(token==null || token.length()>16384 || !token.matches("[A-Za-z0-9_.-]+")) throw unavailable();
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+token)
                    .header("X-Wms-Enterprise-Id",enterprise).header("Accept","application/json")
                    .header("X-Request-Id",UUID.randomUUID().toString());
            if(uri.getPath().equals(base.getPath()+"/source-releases")) request.header("X-Wms-Serial-Release-Proof","1");
            if(uri.getPath().equals(base.getPath()+"/transfer-preparations")) request.header("X-Wms-Serial-Prepare-Proof","1");
            if(json==null) request.GET(); else request.header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
            var future=http.sendAsync(request.build(),info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
            catch(InterruptedException failure) { future.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
            catch(ExecutionException|TimeoutException failure) { future.cancel(true); throw unavailable(); }
            int status=response.statusCode();
            if(!(status==200 || status==400 || status==404 || status==409)) throw unavailable();
            if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) throw unavailable();
            Object decoded=RuntimeMessage.JSON.readValue(response.body(),Map.class);
            if(!(decoded instanceof Map<?,?>)) throw unavailable();
            var result=(Map<String,Object>)decoded;
            if(status!=200) {
                String code=String.valueOf(result.get("code"));
                if(!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw unavailable();
                throw new SerialRegistryConflictException(code,"登记服务拒绝当前事实或状态");
            }
            return result;
        } catch(SerialRegistryConflictException|SerialRegistryUnavailableException failure) { throw failure; }
        catch(RuntimeException failure) { throw unavailable(); }
    }
    private static void validate(Map<String,Object> result,String serial,Set<String> states) {
        if(!states.contains(result.get("state")) || !normalize(serial).equals(result.get("normalizedSerial"))
                || !(result.get("ownerEpoch") instanceof Number epoch) || epoch.longValue()<0
                || !(epoch instanceof Long || epoch instanceof Integer)) throw unavailable();
    }
    private static void require(Map<String,Object> result,String field,String expected) { if(!expected.equals(result.get(field))) throw unavailable(); }
    private static String normalize(String serial) { return serial.trim().toUpperCase(Locale.ROOT); }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static SerialRegistryUnavailableException unavailable() { return new SerialRegistryUnavailableException("登记调用暂不可用或响应不可信，保留本地HOLD与原操作身份"); }

    /** 累计响应上限也覆盖无Content-Length/chunked，避免仅限制头部而允许正文耗尽内存。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(chunk.remaining()>MAX_BODY-bytes.size()) { subscription.cancel(); result.completeExceptionally(unavailable()); return; }
                byte[] copy=new byte[chunk.remaining()]; chunk.get(copy); bytes.writeBytes(copy);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /** 有界停机；不调用可能无限等待在途请求的HttpClient.close。 */
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        http.shutdown(); executor.shutdown();
        try { if(!http.awaitTermination(Duration.ofSeconds(2))) http.shutdownNow();
            if(!executor.awaitTermination(2,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch(InterruptedException failure) { http.shutdownNow(); executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
