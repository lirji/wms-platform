package com.lrj.wms.outbound.order;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 逐身份来源事实保留原输入，唯一约束保护跨任务竞争。 */
public interface OutboundSerialMapper {
    /** 插入不能吞唯一冲突或CHECK错误，来源数量和命令必须一并回滚。 */
    int insert(@Param("row") Map<String,Object> row);
    /** 原命令重放只核对对应原身份，不重新争抢其他活跃事实。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("command") String command,@Param("serial") String serial);
    /** 仅原CLAIMED及版本可获得PICKED回执，旧消息不能重新激活已释放事实。 */
    int posted(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("version") long version,@Param("now") Timestamp now);
}
