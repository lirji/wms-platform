package com.lrj.wms.inventory.jobs.web;

import jakarta.validation.constraints.*;

/** 协议请求模型，约束在开启数据库事务前验证。 */
public final class JobCommandRequests {
    private JobCommandRequests() {}

    /** RetryRequest：在数据库用例开始前校验类型、范围和必填项。 */
    public record RetryRequest(
            @Size(max = 64) @Pattern(regexp = "(?i)RECLAIM|CLAIM|TAKEOVER") String action) {}
}
