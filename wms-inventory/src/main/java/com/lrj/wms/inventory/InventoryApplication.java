package com.lrj.wms.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Inventory独立进程入口，避免通过扫描其他服务实现共享写库。 */
@SpringBootApplication
public class InventoryApplication {
    /** 启动本服务，配置与发布生命周期独立。 */
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
