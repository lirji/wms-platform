package com.lrj.wms.outbound.jobs;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/**
 * 设备 UNKNOWN 巡检入口。只查询、不盲重发。分片留给 S7-02。
 */
@Component
public class DeviceUnknownResultSweep {
    public static final String HANDLER = "deviceUnknownResultSweep";

    @XxlJob(HANDLER)
    public void execute() {
        XxlJobHelper.log("deviceUnknownResultSweep inspect-only; no blind resend");
    }
}
