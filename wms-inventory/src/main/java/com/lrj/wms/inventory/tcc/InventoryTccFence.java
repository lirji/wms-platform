package com.lrj.wms.inventory.tcc;

import javax.sql.DataSource;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.rm.datasource.DataSourceProxy;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把官方 Fence 绑到库存物理库的 Spring 本地事务。禁止 AT DataSourceProxy。
 */
public final class InventoryTccFence {
    private InventoryTccFence() {
    }

    /** 同一 DataSource 上装配 Fence 与业务事务模板。 */
    public static SpringFenceHandler bind(DataSource dataSource, PlatformTransactionManager transactionManager) {
        if (dataSource instanceof DataSourceProxy) {
            throw new IllegalStateException("库存 RM 禁止 Seata AT 数据源代理");
        }
        SpringFenceHandler handler = new SpringFenceHandler();
        SpringFenceHandler.setDataSource(dataSource);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(transactionManager));
        DefaultCommonFenceHandler.get().setFenceHandler(handler);
        return handler;
    }

    /** 测试或手工装配时用同一物理库构造事务管理器。 */
    public static SpringFenceHandler bind(DataSource dataSource) {
        return bind(dataSource, new DataSourceTransactionManager(dataSource));
    }
}
