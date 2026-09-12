package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 自动执行默认关闭；开启时必须具备真实TC审计、受控服务JWT和固定cell配置。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.fulfillment.execution.enabled",havingValue="true")
public class AllocationExecutionConfiguration {
    @Bean(destroyMethod="close")
    SeataTmDriver seataTmDriver(Environment env,TcEvidenceScope scope,JdbcTcStatusPort audit) {
        if(!env.getProperty("wms.messaging.enabled",Boolean.class,false)) throw new IllegalArgumentException("自动履约需要确认和授权消息链路");
        if(!env.getProperty("wms.fulfillment.execution.network-isolation-confirmed",Boolean.class,false))
            throw new IllegalArgumentException("TC调用必须显式确认网络隔离，配置声明不代替ACL验证");
        return new SeataTmDriver(scope,List.of(env.getRequiredProperty("wms.fulfillment.execution.tc-servers").split(",")),
                env.getProperty("wms.fulfillment.execution.access-key"),env.getProperty("wms.fulfillment.execution.secret-key"));
    }
    @Bean(destroyMethod="close")
    WarehouseTryHttpClient warehouseTryHttpClient(Environment env,TcEvidenceScope scope) {
        var parsed=RuntimeMessage.JSON.readTree(env.getRequiredProperty("wms.fulfillment.execution.cells-json"));
        if(!parsed.isObject()||parsed.size()<1||parsed.size()>64) throw new IllegalArgumentException("必须配置固定cell根地址对象");
        var cells=new LinkedHashMap<String,URI>();
        parsed.properties().forEach(entry->{if(!entry.getValue().isString())throw new IllegalArgumentException("cell地址必须为字符串");cells.put(entry.getKey(),URI.create(entry.getValue().asString()));});
        Path directory=Path.of(env.getRequiredProperty("wms.fulfillment.execution.token-directory")).toAbsolutePath().normalize();
        if(!Files.isDirectory(directory)) throw new IllegalArgumentException("受控服务JWT目录不存在");
        return new WarehouseTryHttpClient(cells,scope.clusterId(),enterprise->readToken(directory,enterprise),
                env.getProperty("wms.fulfillment.execution.allow-http",Boolean.class,false));
    }
    /** 原子替换企业摘要文件支持轮换，读取不跟随符号链接且最多16KiB。 */
    static String readToken(Path directory,String enterprise) {
        Path file=directory.resolve(RuntimeMessage.hash(enterprise)+".jwt");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw AllocationExecutionService.error("RM_CREDENTIAL_UNAVAILABLE");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(16385);if(bytes.length>16384) throw new java.io.IOException("size");
            return new String(bytes,StandardCharsets.US_ASCII).trim();
        } catch(java.io.IOException unavailable) {throw AllocationExecutionService.error("RM_CREDENTIAL_UNAVAILABLE");}
    }
    @Bean AllocationExecutionService allocationExecutionService(SqlSessionFactory sessions,TcEvidenceScope scope) {
        return new AllocationExecutionService(sessions,scope,Clock.systemUTC());
    }
    @Bean AllocationExecutionWorker allocationExecutionWorker(SqlSessionFactory sessions,SeataTmDriver tm,WarehouseTryHttpClient warehouse,
            JdbcTcStatusPort audit,TcEvidenceScope scope) {
        return new AllocationExecutionWorker(sessions,tm,warehouse,audit,scope,Clock.systemUTC());
    }
    @Bean(destroyMethod="close")
    ExecutionSchedule allocationExecutionSchedule(AllocationExecutionWorker worker,Environment env) {
        String[] values=env.getRequiredProperty("wms.fulfillment.execution.enterprises").split(",");
        var enterprises=Arrays.stream(values).map(String::trim).toList();
        if(enterprises.isEmpty()||enterprises.size()>64||new HashSet<>(enterprises).size()!=enterprises.size()
                ||enterprises.stream().anyMatch(e->e.isBlank()||e.length()>64)) throw new IllegalArgumentException("执行租户须明确且不重复，最多64项");
        return new ExecutionSchedule(worker,enterprises);
    }
    /** 单线程每次只推进一个企业的一个动作，轮询租户防止热点企业占满整个调度批次。 */
    static final class ExecutionSchedule implements AutoCloseable {
        private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"allocation-execution");t.setDaemon(true);return t;});
        private int next;
        ExecutionSchedule(AllocationExecutionWorker worker,List<String> enterprises) {
            executor.scheduleWithFixedDelay(()->{
                String enterprise=enterprises.get(next);next=(next+1)%enterprises.size();
                try {worker.executeOne(enterprise);}
                catch(RuntimeException error) {org.slf4j.LoggerFactory.getLogger(ExecutionSchedule.class).warn("allocation execution pending code={}",error instanceof FulfillmentException known?known.code():"EXECUTION_UNAVAILABLE");}
            },500,250,TimeUnit.MILLISECONDS);
        }
        /** 先停止调度，最多等30秒；未完成动作靠原持久化代际恢复。 */
        @Override public void close(){executor.shutdown();try{if(!executor.awaitTermination(30,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}}
    }
}
