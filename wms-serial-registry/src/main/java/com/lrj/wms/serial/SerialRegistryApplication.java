package com.lrj.wms.serial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 序列号登记独立进程，不扫描入出库或库存实现。 */
@SpringBootApplication
public class SerialRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(SerialRegistryApplication.class, args);
    }
}
