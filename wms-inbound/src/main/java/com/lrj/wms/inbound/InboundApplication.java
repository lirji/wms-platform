package com.lrj.wms.inbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Inbound独立进程入口，避免通过扫描其他服务实现共享写库。 */
@SpringBootApplication
public class InboundApplication {
    /** 启动本服务，配置与发布生命周期独立。 */
    public static void main(String[] args) {
        SpringApplication.run(InboundApplication.class, args);
    }
}
