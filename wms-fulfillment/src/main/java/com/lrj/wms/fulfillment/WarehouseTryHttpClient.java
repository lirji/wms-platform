package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.tcc.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** 固定cell允许列表和受控企业服务JWT；不接受调用者URL，不转发操作员凭据。 */
public final class WarehouseTryHttpClient implements WarehouseTryPort,AutoCloseable {
    private final Map<String,URI> cells;
    private final String cluster;
    private final Function<String,String> tokens;
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private volatile boolean closed;
    public WarehouseTryHttpClient(Map<String,URI> cells,String cluster,Function<String,String> tokens,boolean allowHttp) {
        if(cells==null||cells.isEmpty()||cells.size()>64) throw new IllegalArgumentException("库存cell配置须为1到64项");
        cells.forEach((cell,uri)->{
            if(!cell.matches("[A-Za-z0-9_.-]{1,64}")||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||!(uri.getPath().isEmpty()||"/".equals(uri.getPath()))||!("https".equals(uri.getScheme())||allowHttp&&"http".equals(uri.getScheme())))
                throw new IllegalArgumentException("库存cell须为明确HTTP(S)根地址，HTTP需显式允许");
        });
        this.cells=Map.copyOf(cells);this.cluster=cluster;this.tokens=tokens;
        executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"warehouse-try-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).executor(executor).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 受理执行命令前检查路由是否可解析，但不联网探测或自动改仓。 */
    public void requireCell(String cell) {if(!cells.containsKey(cell)) throw AllocationExecutionService.error("RM_CELL_NOT_CONFIGURED");}
    @Override public WarehouseTryResult reserve(String xid,WarehouseTryRequest request) {
        if(closed) throw AllocationExecutionService.error("RM_CLIENT_CLOSED");
        requireCell(request.cellId());
        try {
            String token=tokens.apply(request.enterpriseId());
            if(token==null||token.length()>16384||!token.matches("[A-Za-z0-9_.-]+")) throw AllocationExecutionService.error("RM_CREDENTIAL_UNAVAILABLE");
            String warehouse=java.net.URLEncoder.encode(request.warehouseId(),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
            var httpRequest=HttpRequest.newBuilder(cells.get(request.cellId()).resolve("/internal/wms/v1/warehouses/"+warehouse+"/tcc/tries"))
                    .timeout(Duration.ofMillis(1500)).header("Authorization","Bearer "+token).header("TX_XID",xid)
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .header("X-Request-Id",UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(RuntimeMessage.JSON.writeValueAsString(request))).build();
            var future=http.sendAsync(httpRequest,info->new LimitedBody());HttpResponse<byte[]> response;
            try {response=future.get(1500,TimeUnit.MILLISECONDS);}
            catch(InterruptedException interrupted) {future.cancel(true);Thread.currentThread().interrupt();throw AllocationExecutionService.error("RM_TRY_UNKNOWN");}
            catch(ExecutionException|TimeoutException unknown) {future.cancel(true);throw AllocationExecutionService.error("RM_TRY_UNKNOWN");}
            if(response.statusCode()!=200) throw AllocationExecutionService.error("RM_TRY_UNCONFIRMED");
            if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw AllocationExecutionService.error("TRY_RECEIPT_MISMATCH");
            var node=RuntimeMessage.JSON.readTree(response.body());
            if(!node.path("branchId").isIntegralNumber()||!node.path("branchId").canConvertToLong()||node.path("branchId").asLong()<1
                    ||!node.path("routeEpoch").isIntegralNumber()||!node.path("routeEpoch").canConvertToLong()) throw AllocationExecutionService.error("TRY_RECEIPT_MISMATCH");
            for(String field:List.of("xid","actionName","reservationId","allocationId","attemptId","state"))
                if(!node.path(field).isString()||node.path(field).asString().isBlank()) throw AllocationExecutionService.error("TRY_RECEIPT_MISMATCH");
            var result=RuntimeMessage.JSON.treeToValue(node,WarehouseTryResult.class);
            String action="WmsReserveV1-"+Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(
                    RuntimeMessage.hash(cluster+"\u001f"+request.cellId())));
            if(!xid.equals(result.xid())||!request.attemptId().equals(result.attemptId())||!request.allocationId().equals(result.allocationId())
                    ||request.routeEpoch()!=result.routeEpoch()||!action.equals(result.actionName())||result.reservationId().length()>64
                    ||!Set.of("TRIED","CONFIRMED").contains(result.state())) throw AllocationExecutionService.error("TRY_RECEIPT_MISMATCH");
            return result;
        } catch(FulfillmentException known) {throw known;}
        catch(RuntimeException unknown) {throw AllocationExecutionService.error("RM_TRY_UNKNOWN");}
    }
    /** 覆盖chunked累积正文，避免不限量BodyHandlers.ofString。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){if(chunk.remaining()>65536-bytes.size()){subscription.cancel();result.completeExceptionally(AllocationExecutionService.error("RM_RESPONSE_TOO_LARGE"));return;}
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
    /** 停止接纳，最多等待2秒后取消网络和线程池；未知请求仍由持久化进度恢复。 */
    @Override public void close(){
        closed=true;http.shutdown();executor.shutdown();
        try {if(!http.awaitTermination(Duration.ofSeconds(2)))http.shutdownNow();if(!executor.awaitTermination(2,TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException interrupted){http.shutdownNow();executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
