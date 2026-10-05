package com.lrj.wms.driver.protocol;

/** 运行时状态由发现/健康/适配器共同决定，禁止把配置里的 active 直接当成可执行。 */
public enum RuntimeStatus {
    DECLARED,
    REGISTERED,
    ACTIVE,
    DEGRADED,
    UNAVAILABLE,
    DISABLED
}
