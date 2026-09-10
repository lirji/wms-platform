package com.lrj.wms.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Outbound独立进程入口，避免通过扫描其他服务实现共享写库。 */
@SpringBootApplication
public class OutboundApplication {
    /** 启动本服务，配置与发布生命周期独立。 */
    public static void main(String[] args) {
        SpringApplication.run(OutboundApplication.class, args);
    }
}
