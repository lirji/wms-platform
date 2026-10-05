package com.lrj.wms.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 全局履约独立进程，不扫描入出库或库存实现。 */
@SpringBootApplication
public class FulfillmentApplication {
    /** 命令行入口沿用显式配置与隔离检查，不能以演示默认值覆盖业务环境。 */
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentApplication.class, args);
    }
}
