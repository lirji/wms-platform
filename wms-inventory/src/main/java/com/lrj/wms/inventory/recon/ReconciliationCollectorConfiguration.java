package com.lrj.wms.inventory.recon;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认关闭；全部旧来源和库存写节点退出后，部署负责人才能启用受信历史采集。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.reconciliation.collector.enabled",havingValue="true")
@EnableConfigurationProperties(ReconciliationCollectorConfiguration.Properties.class)
public class ReconciliationCollectorConfiguration {
    @ConfigurationProperties("wms.reconciliation.collector")
    public record Properties(boolean enabled,URI inboundUrl,URI outboundUrl,Path tokenDirectory,boolean allowHttp) { }

    /** 两来源仅从类型化配置取得，不接受请求提供URL；跨信任边界必须使用HTTPS。 */
    @Bean(destroyMethod="close")
    ReconciliationSourceHttpClient reconciliationSourceHttpClient(Properties properties) {
        if(properties.inboundUrl()==null || properties.outboundUrl()==null || properties.tokenDirectory()==null)
            throw new IllegalArgumentException("可信采集必须配置两个来源及服务凭据目录");
        var urls=Map.of("wms-inbound",properties.inboundUrl(),"wms-outbound",properties.outboundUrl());
        if(!properties.allowHttp() && urls.values().stream().anyMatch(uri -> "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("明文HTTP采集需显式allow-http，跨信任边界使用HTTPS");
        Path directory=properties.tokenDirectory().toAbsolutePath().normalize();
        if(!Files.isDirectory(directory)) throw new IllegalArgumentException("来源服务凭据目录不存在");
        return new ReconciliationSourceHttpClient(urls,e -> token(directory,e),Duration.ofSeconds(4));
    }
    @Bean
    ReconciliationCollector reconciliationCollector(SqlSessionFactory sessions,ReconciliationSourceHttpClient client) {
        return new ReconciliationCollector(sessions,Clock.systemUTC(),client);
    }
    /** 凭据控制器按企业摘要文件名原子轮换；逐次读取，不跟随符号链接、不读取任意请求路径。 */
    static String token(Path directory,String enterprise) {
        Path file=directory.resolve(RuntimeMessage.hash(enterprise)+".jwt");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(16385);if(bytes.length>16384) throw unavailable();
            return new String(bytes,StandardCharsets.US_ASCII).trim();
        } catch(java.io.IOException failure) {throw unavailable();}
    }
    private static com.lrj.wms.inventory.jobs.JobRunException unavailable() {
        return new com.lrj.wms.inventory.jobs.JobRunException("SOURCE_UNAVAILABLE","该企业的来源服务凭据暂不可用");
    }
}
