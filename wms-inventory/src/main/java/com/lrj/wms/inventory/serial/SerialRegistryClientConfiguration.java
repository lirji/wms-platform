package com.lrj.wms.inventory.serial;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 外部凭据控制器按企业原子替换JWT文件；库存不得转发操作员令牌或自行签发服务身份。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.serial.client.enabled",havingValue="true")
public class SerialRegistryClientConfiguration {
    @Bean(destroyMethod="close")
    SerialRegistryHttpClient serialRegistryHttpClient(Environment environment) {
        URI uri=URI.create(environment.getRequiredProperty("wms.serial.client.base-url"));
        if("http".equals(uri.getScheme()) && !environment.getProperty("wms.serial.client.allow-http",Boolean.class,false))
            throw new IllegalArgumentException("HTTP登记链路需显式配置allow-http；跨信任边界使用HTTPS");
        Path directory=Path.of(environment.getRequiredProperty("wms.serial.client.token-directory")).toAbsolutePath().normalize();
        if(!Files.isDirectory(directory)) throw new IllegalArgumentException("登记服务凭据目录不存在");
        return new SerialRegistryHttpClient(uri,enterprise -> readToken(directory,enterprise),Duration.ofMillis(1500));
    }
    /** 固定摘要文件名防路径注入；不跟随文件符号链接，逐次有界读取以支持轮换。 */
    static String readToken(Path directory,String enterprise) {
        Path file=directory.resolve(SerialRegistryHttpClient.digest(enterprise)+".jwt");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new SerialRegistryUnavailableException("登记凭据必须是受控普通文件");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(16385);
            if(bytes.length>16384) throw new java.io.IOException("token size");
            return new String(bytes,StandardCharsets.US_ASCII).trim();
        } catch(java.io.IOException failure) { throw new SerialRegistryUnavailableException("该企业的登记服务凭据暂不可用"); }
    }
}
