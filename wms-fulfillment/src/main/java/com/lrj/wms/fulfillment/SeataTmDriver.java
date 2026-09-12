package com.lrj.wms.fulfillment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.rpc.netty.TmNettyRemotingClient;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransactionContext;

/** 官方TM调用适配器；调用结果只表示RPC结果，业务放行仍依赖独立的持久化TC证据。 */
public final class SeataTmDriver implements AllocationTmPort, AutoCloseable {
    private final TcEvidenceScope scope;
    private final Semaphore admission=new Semaphore(4);
    private final TmNettyRemotingClient client;
    private volatile boolean closed;

    /** 一个JVM固定一套TC环境，不以热改SDK全局配置切换其他集群。凭据只用于SDK注册。 */
    public SeataTmDriver(TcEvidenceScope scope,List<String> servers,String accessKey,String secretKey) {
        this.scope=java.util.Objects.requireNonNull(scope);
        if (servers==null || servers.isEmpty() || servers.size()>3) throw new IllegalArgumentException("TC地址必须明确且最多3个");
        for(String endpoint:servers) {
            java.net.URI uri;
            try {uri=java.net.URI.create("tcp://"+endpoint);} catch(RuntimeException invalid) {throw new IllegalArgumentException("无效TC地址");}
            if (endpoint.length()>255 || uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535
                    || uri.getUserInfo()!=null || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("TC地址只接受主机和端口");
        }
        boolean hasAccess=accessKey!=null&&!accessKey.isBlank(),hasSecret=secretKey!=null&&!secretKey.isBlank();
        if (hasAccess!=hasSecret) throw new IllegalArgumentException("TC凭据必须完整提供");
        // 0会使官方SDK恢复默认重试次数；1才表示每次业务调度只尝试一次提交/回滚RPC。
        var settings=Map.of("service.vgroupMapping."+scope.transactionGroup(),"default",
                "service.default.grouplist",String.join(",",servers),
                "client.tm.commitRetryCount","1","client.tm.rollbackRetryCount","1",
                "transport.rpcTmRequestTimeout","1500","transport.enableTmClientBatchSendRequest","false",
                "transport.threadFactory.clientSelectorThreadSize","2");
        synchronized (SeataTmDriver.class) {
            for(var setting:settings.entrySet()) {
                String previous=System.getProperty(setting.getKey());
                if (previous!=null&&!previous.equals(setting.getValue())) throw new IllegalStateException("TC SDK配置已属于另一环境或预算");
            }
            settings.forEach(System::setProperty);
            TMClient.init(scope.applicationId(),scope.transactionGroup(),hasAccess?accessKey:null,hasSecret?secretKey:null);
            client=TmNettyRemotingClient.getInstance();
            if (client.getRpcRequestTimeout()!=1500 || client.isEnableClientBatchSendRequest())
                throw new IllegalStateException("TC SDK已在不同预算下初始化，必须通过进程重启隔离");
        }
    }

    public TcEvidenceScope scope() {return scope;}

    /** 仅持有持久化launch所有权的调用方可调用一次；异常表示结果未知，不允许盲目再begin。 */
    public String begin(String attemptId,int timeoutMillis) {
        if (attemptId==null||attemptId.isBlank()||attemptId.length()>48||timeoutMillis<1000||timeoutMillis>60000)
            throw new IllegalArgumentException("TM启动需要原attempt和1至60秒事务预算");
        enter();
        try {
            var transaction=GlobalTransactionContext.createNew();
            transaction.begin(timeoutMillis,"allocation:"+attemptId);
            return transaction.getXid();
        } catch (Exception unknown) {throw new FulfillmentException("TC_BEGIN_UNKNOWN","TC启动结果未知，保留原启动代际等待恢复");}
        finally {RootContext.unbind();admission.release();}
    }

    /** 用原XID恢复Launcher；不再次begin，不把本地状态写成Committed证明。 */
    public void commit(String xid) {finish(xid,true);}

    /** 回滚请求也必须关联原XID；二阶段由TC调度，不能调用库存的Confirm/Cancel HTTP。 */
    public void rollback(String xid) {finish(xid,false);}

    private void finish(String xid,boolean commit) {
        if (xid==null||xid.isBlank()||xid.length()>128) throw new IllegalArgumentException("必须提供原XID");
        enter();
        try {
            var transaction=GlobalTransactionContext.reload(xid);
            if (commit) transaction.commit(); else transaction.rollback();
        } catch (Exception unknown) {throw new FulfillmentException(commit?"TC_COMMIT_UNKNOWN":"TC_ROLLBACK_UNKNOWN","TC请求结果未知，保留原XID等待证据");}
        finally {RootContext.unbind();admission.release();}
    }

    /** 不在调用线程排队；拒绝在其他全局事务上下文内偷偷发起独立分配。 */
    private synchronized void enter() {
        if (closed) throw new FulfillmentException("TC_CLIENT_CLOSED","TC客户端已关闭");
        if (RootContext.getXID()!=null) throw new FulfillmentException("TM_CONTEXT_ALREADY_BOUND","调用线程已有全局事务上下文");
        if (!admission.tryAcquire()) throw new FulfillmentException("TC_ADMISSION_REJECTED","TC调用并发已达到预算");
    }

    /** 生命周期归当前进程所有；SDK全局配置不能在同JVM关闭后换环境复用。 */
    @Override public void close() {
        synchronized(this) { if(closed) return; closed=true; }
        // 先停止新调用，再给已接纳RPC最多30秒收尾；超时销毁后调用方仍按结果未知恢复。
        boolean drained=false;
        try {drained=admission.tryAcquire(4,30,java.util.concurrent.TimeUnit.SECONDS);}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        finally {client.destroy(); if(drained) admission.release(4);}
    }
}
