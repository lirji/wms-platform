package com.lrj.wms.runtime.messaging.persistence;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Param;

/** 仅当前服务本库的队列观测；队列表名由服务端枚举选择。 */
public interface MessageQueueMetricsMapper {
    /** 最多计数1001条，避免监控在故障积压时进一步拖垮数据库。 */
    int boundedDepth(@Param("queue") String queue, @Param("status") String status);

    /** 状态和创建时间索引直接定位最旧一条，不扫描完整队列。 */
    Long oldestAgeMicros(@Param("queue") String queue, @Param("status") String status, @Param("now") Timestamp now);
}
