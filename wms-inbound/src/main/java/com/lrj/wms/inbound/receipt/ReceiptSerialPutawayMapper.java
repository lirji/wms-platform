package com.lrj.wms.inbound.receipt;

import java.util.*;
import org.apache.ibatis.annotations.Param;

/** 来源只管理原收货批次的任务占用，不替代库存或全局登记身份权威。 */
public interface ReceiptSerialPutawayMapper {
    /** 数据库唯一键防止两个任务领取同一批次身份。 */
    int insert(Map<String,Object> row);
    /** 重放只能匹配原任务与原来源命令。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("receipt") String receipt,@Param("serial") String serial);
    /** 每批最多200个身份；质检新版本不能降级已经上架受理的身份。 */
    List<String> claimed(@Param("e") String e,@Param("w") String w,@Param("receipt") String receipt);
}
