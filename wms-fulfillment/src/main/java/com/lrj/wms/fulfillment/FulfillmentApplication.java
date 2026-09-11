package com.lrj.wms.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 全局履约独立进程，不扫描入出库或库存实现。 */
@SpringBootApplication
public class FulfillmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentApplication.class, args);
    }
}
