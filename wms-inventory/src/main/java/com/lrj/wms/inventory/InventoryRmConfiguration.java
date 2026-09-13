package com.lrj.wms.inventory;

import com.lrj.wms.inventory.tcc.*;
import com.lrj.wms.inventory.inventory.domain.CommandDigest;
import com.lrj.wms.runtime.db.DatabaseBudget;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式启用原生RM；路由必须预先由运维持久化，不按当前参数自动创建权威仓路由。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.tcc.rm.enabled",havingValue="true")
public class InventoryRmConfiguration {
    @Bean
    RuntimeTccCoordinator runtimeTccCoordinator(DataSource dataSource,org.flywaydb.core.Flyway flyway,DatabaseBudget budget,PlatformTransactionManager transactionManager,
            org.apache.seata.rm.fence.SpringFenceHandler inventoryTccFence,Environment env) {
        String cell=env.getRequiredProperty("wms.tcc.rm.cell-id"),cluster=env.getRequiredProperty("wms.tcc.cluster-id");
        if(!cell.matches("[A-Za-z0-9_.-]{1,64}") || !cluster.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("TC集群与cell标识无效");
        // 官方Fence action_name为VARCHAR(64)；Base64URL保留全部256位身份且总长56。
        String action="WmsReserveV1-"+java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.HexFormat.of().parseHex(CommandDigest.v1Parts(cluster,cell)));
        var factory=InventoryPersistence.sessions(dataSource,new SpringManagedTransactionFactory(),budget);
        return new RuntimeTccCoordinator(new SqlSessionTemplate(factory),new TransactionTemplate(transactionManager),inventoryTccFence,
                java.time.Clock.systemUTC(),cell,action);
    }
    @Bean(destroyMethod="close")
    SeataRmDriver seataRmDriver(RuntimeTccCoordinator coordinator,Environment env) {
        // 2.6.0原生RM没有TM的access/secret参数；不能静默声称RM注册已获凭据保护。
        if(!env.getProperty("wms.tcc.rm.network-isolation-confirmed",Boolean.class,false))
            throw new IllegalArgumentException("原生RM必须部署在已配置网络ACL的TC私网内");
        if(env.containsProperty("wms.tcc.access-key") || env.containsProperty("wms.tcc.secret-key"))
            throw new IllegalArgumentException("2.6.0原生RM不支持此凭据接口，不能忽略安全配置");
        return new SeataRmDriver(coordinator,env.getRequiredProperty("wms.tcc.transaction-group"),
                java.util.Arrays.stream(env.getRequiredProperty("wms.tcc.servers").split(",")).map(String::trim).toList(),
                java.util.Arrays.stream(env.getProperty("wms.tcc.xid-addresses",env.getRequiredProperty("wms.tcc.servers")).split(",")).map(String::trim).toList());
    }
}
