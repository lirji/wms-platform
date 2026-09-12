package com.lrj.wms.runtime.messaging.persistence;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 来源协议发布队列，只操作该SqlSessionFactory所拥有的单个来源物理库。 */
public interface SourceOutboxMapper {
    /** 每次只领取一个到期事件，避免一批消息等待网络时整批租约过期。 */
    Map<String, Object> lockNext(@Param("now") Timestamp now);
    /** 领取和原租约比较，恢复者必须获得新代际。 */
    int claim(@Param("eventId") String eventId, @Param("epoch") long epoch,
            @Param("now") Timestamp now, @Param("until") Timestamp until);
    /** 正文及原执行身份来自本库不可变的T1记录；查询完成释放连接再发送。 */
    Map<String, Object> metadata(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);
    /** 消息确认、重试和隔离都不能绕过代际比较。 */
    int finish(@Param("eventId") String eventId, @Param("epoch") long epoch, @Param("status") String status,
            @Param("error") String error, @Param("now") Timestamp now, @Param("next") Timestamp next);
}
