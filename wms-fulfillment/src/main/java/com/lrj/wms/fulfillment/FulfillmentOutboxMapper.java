package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 履约事件按单条短事务领取，SQL集中在本库Mapper。 */
public interface FulfillmentOutboxMapper {
    /** 只领取到期或租约到期的一项，多实例跳过已锁项。 */
    Map<String,Object> lockNext(@Param("now") Timestamp now);
    /** 原代际条件领取；网络调用不占用此事务连接。 */
    int claim(@Param("eventId") String eventId,@Param("epoch") long epoch,@Param("now") Timestamp now,@Param("lease") Timestamp lease);
    /** 发布确认与业务完成不同；迟到执行器不得改变新代际状态。 */
    int finish(@Param("eventId") String eventId,@Param("epoch") long epoch,@Param("state") String state,
            @Param("error") String error,@Param("now") Timestamp now,@Param("next") Timestamp next);
}
