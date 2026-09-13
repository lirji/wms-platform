package com.lrj.wms.inventory.tcc;

import java.util.List;
import java.util.Map;
import org.apache.seata.core.model.BranchType;
import org.apache.seata.core.rpc.netty.RmNettyRemotingClient;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.rm.DefaultResourceManager;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.tcc.TCCResource;
import org.apache.seata.rm.tcc.api.BusinessActionContext;

/** 原生Seata RM注册；一个进程固定一个物理cell，资源身份不随重启改变。 */
public final class SeataRmDriver implements AutoCloseable,com.lrj.wms.runtime.observability.RuntimeDependencyCheck {
    private final RuntimeTccCoordinator coordinator;
    private final RmNettyRemotingClient client;
    private volatile boolean closed;
    private final java.util.Set<String> registered=new java.util.HashSet<>();
    private final String group,applicationId;
    private final java.util.concurrent.ConcurrentMap<io.netty.channel.Channel,java.util.Set<String>> channelAliases=new java.util.concurrent.ConcurrentHashMap<>();
    private final org.apache.seata.core.rpc.netty.ChannelEventListener channelListener=new org.apache.seata.core.rpc.netty.ChannelEventListener() {
        @Override public void onChannelConnected(io.netty.channel.Channel channel) {channelAliases.put(channel,java.util.concurrent.ConcurrentHashMap.newKeySet());}
        @Override public void onChannelDisconnected(io.netty.channel.Channel channel) {channelAliases.remove(channel);healthyAt=0;}
    };
    private final List<String> servers,xidAddresses;
    private final java.util.concurrent.ScheduledExecutorService probe=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"wms-tcc-rm-health");thread.setDaemon(true);return thread;});
    private volatile long healthyAt;

    public SeataRmDriver(RuntimeTccCoordinator coordinator,String group,List<String> servers,List<String> xidAddresses) {
        this.coordinator=coordinator;this.group=group;
        this.servers=servers==null?List.of():List.copyOf(servers);
        this.xidAddresses=xidAddresses==null?this.servers:List.copyOf(xidAddresses);
        if(this.xidAddresses.isEmpty() || this.xidAddresses.size()>16) throw new IllegalArgumentException("TC通告地址必须明确且最多16个");
        if(group==null || !group.matches("[A-Za-z0-9_.-]{1,32}") || servers==null || servers.isEmpty() || servers.size()>3)
            throw new IllegalArgumentException("RM必须明确TC组及最多3个地址");
        for(var endpoint:java.util.stream.Stream.concat(servers.stream(),this.xidAddresses.stream()).distinct().toList()) {
            var uri=java.net.URI.create("tcp://"+endpoint);
            if(endpoint.length()>255 || uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535 || uri.getUserInfo()!=null
                    || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("TC地址无效");
        }
        var settings=Map.of("service.vgroupMapping."+group,"default","service.default.grouplist",String.join(",",servers),
                "transport.rpcRmRequestTimeout","1500","transport.enableRmClientBatchSendRequest","false",
                "transport.threadFactory.clientSelectorThreadSize","2","transport.threadFactory.workerThreadSize","4");
        synchronized(SeataRmDriver.class) {
            for(var entry:settings.entrySet()) {
                var previous=System.getProperty(entry.getKey());
                if(previous!=null&&!previous.equals(entry.getValue()))throw new IllegalStateException("RM SDK已有不同环境配置");
            }
            settings.forEach(System::setProperty);
            DefaultCommonFenceHandler.get().setFenceHandler(coordinator);
            // TC无资源可用时可能按RM应用回退路由；不同物理cell不能共用应用身份。
            // TC application_id上限32，client_id还会追加网络身份；应用短标识保留128位哈希。
            applicationId=application(coordinator.actionName());
            client=RmNettyRemotingClient.getInstance();
            client.registerChannelEventListener(channelListener);
            RMClient.init(applicationId,group);
            // RM默认没有全局状态响应处理器；显式复用官方处理器，批量关闭时两张关联表始终为空。
            client.registerProcessor(org.apache.seata.core.protocol.MessageType.TYPE_GLOBAL_STATUS_RESULT,
                    new org.apache.seata.core.rpc.processor.client.ClientOnResponseProcessor(new java.util.concurrent.ConcurrentHashMap<>(),
                            client.getFutures(),new java.util.concurrent.ConcurrentHashMap<>(),client.getTransactionMessageHandler()),null);
            if(client.getRpcRequestTimeout()!=1500 || client.isEnableClientBatchSendRequest()) throw new IllegalStateException("RM SDK已用不同预算初始化");
            registerResource(coordinator.actionName());
            for(String action:coordinator.historicalResources()) registerResource(action);
        }
        probe.scheduleWithFixedDelay(()->{
            try {
                var historical=coordinator.historicalResources();
                for(String action:historical) registerResource(action);
                // 不创建事务的状态查询证明实际TC协议可达；Finished仅表示探针ID不存在，不作业务证据。
                var status=DefaultResourceManager.get().getGlobalStatus(BranchType.TCC,servers.getFirst()+":0");
                healthyAt=status==org.apache.seata.core.model.GlobalStatus.Finished && restoreOriginalApplications(historical)?System.nanoTime():0;
            } catch(RuntimeException unavailable){healthyAt=0;}
        },0,5,java.util.concurrent.TimeUnit.SECONDS);
    }
    /** TCC不跨application回退；使用官方登记协议在同一受控连接补原应用/资源别名，并逐连接确认。 */
    private boolean restoreOriginalApplications(List<String> resources) {
        var historical=resources.stream().filter(action->!action.equals(coordinator.actionName())).toList();
        if(historical.isEmpty()) return true;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);int sent=0;
        if(channelAliases.isEmpty()) return false;
        for(var entry:channelAliases.entrySet()) {
            var channel=entry.getKey();if(!channel.isActive()) {channelAliases.remove(channel);continue;}
            // 先明确本连接原生应用已登记，避免TCP刚连上时历史别名抢先成为该连接的身份。
            var actions=new java.util.ArrayList<String>();actions.add(coordinator.actionName());actions.addAll(historical);
            for(String action:actions) {
                if(entry.getValue().contains(action)) continue;
                if(sent++>=8 || System.nanoTime()>=deadline) return false;
                var request=new org.apache.seata.core.protocol.RegisterRMRequest(application(action),group);request.setResourceIds(action);
                try {
                    Object response=client.sendSyncRequest(channel,request);
                    if(!(response instanceof org.apache.seata.core.protocol.RegisterRMResponse registered) || !registered.isIdentified()) return false;
                    entry.getValue().add(action);
                } catch(java.util.concurrent.TimeoutException unknown) {return false;}
            }
        }
        return !channelAliases.isEmpty();
    }
    private static String application(String action) {
        byte[] identity=java.util.HexFormat.of().parseHex(com.lrj.wms.inventory.inventory.domain.CommandDigest.v1Parts(action));
        return "wms-rm-"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(identity,16));
    }
    /** 原资源逐一恢复注册；历史资源也指向只读终态回执分支，不增加Try入口。 */
    private synchronized void registerResource(String action) {
        if(registered.contains(action)) return;
        try {
            var resource=new TCCResource();
            resource.setActionName(action);resource.setResourceGroupId(group);resource.setAppName(applicationId);
            resource.setTargetBean(coordinator);
            resource.setPrepareMethod(RuntimeTccCoordinator.class.getMethod("prepare",BusinessActionContext.class));
            resource.setCommitMethod(RuntimeTccCoordinator.class.getMethod("confirm",BusinessActionContext.class));
            resource.setRollbackMethod(RuntimeTccCoordinator.class.getMethod("cancel",BusinessActionContext.class));
            resource.setCommitArgsClasses(new Class<?>[]{BusinessActionContext.class});resource.setRollbackArgsClasses(new Class<?>[]{BusinessActionContext.class});
            resource.setPhaseTwoCommitKeys(new String[]{"context"});resource.setPhaseTwoRollbackKeys(new String[]{"context"});
            DefaultResourceManager.get().registerResource(resource);registered.add(action);
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("RM回调声明不完整",failure);}
    }
    /** 官方登记调用不在数据库事务内；超时不转成无分支或重新登记。 */
    public long register(String xid,String data) {
        if(closed)throw new com.lrj.wms.inventory.inventory.InventoryException("TCC_CLIENT_CLOSED","RM正在停机");
        requireXid(xid);
        try {return DefaultResourceManager.get().branchRegister(BranchType.TCC,coordinator.actionName(),null,xid,data,null);}
        catch(Exception unknown){throw new com.lrj.wms.inventory.inventory.InventoryException("TCC_REGISTRATION_UNKNOWN","TC登记结果未知，禁止重开分支");}
    }
    /** 在持久化意图前校验TC通告身份；NAT通告地址可不同于SDK实际连接地址。 */
    public void requireXid(String xid) {
        if(xid==null || xidAddresses.stream().noneMatch(endpoint->xid.startsWith(endpoint+":")
                && xid.substring(endpoint.length()+1).matches("[1-9][0-9]{0,19}")))
            throw new com.lrj.wms.inventory.inventory.InventoryException("TCC_XID_SERVER_REJECTED","XID不属于明确配置的TC通告地址");
    }
    /** 健康HTTP只读已采样结果；连接建立超时不会阻塞健康请求。 */
    @Override public org.springframework.boot.health.contributor.Health health(){
        long at=healthyAt;
        return !closed && at!=0 && System.nanoTime()-at<java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                ?org.springframework.boot.health.contributor.Health.up().build():org.springframework.boot.health.contributor.Health.down().build();
    }
    /** HTTP由Spring优雅停机先排空；未完成二阶段交回TC按原分支重试。 */
    @Override public synchronized void close(){if(!closed){closed=true;healthyAt=0;probe.shutdownNow();client.unregisterChannelEventListener(channelListener);client.destroy();channelAliases.clear();}}
}
