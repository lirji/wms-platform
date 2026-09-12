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
    /** 本行存在序列事实时，禁止退回只含数量的发运。 */
    int serialLine(@Param("e") String e,@Param("w") String w,@Param("line") String line);
    /** 原行原代际定位身份；历史事实也用于原发运重放核对。 */
    Map<String,Object> lockForShipment(@Param("row") Map<String,Object> row);
    /** 发运额度与来源实物/命令同事务占用，条件更新防双发。 */
    int claimShipment(@Param("row") Map<String,Object> row);
    /** T3只能确认原发运占用，不能改写另一命令。 */
    int shipmentPosted(@Param("e") String e,@Param("w") String w,@Param("command") String command,
            @Param("serial") String serial,@Param("epoch") long epoch,@Param("now") Timestamp now);
    /** 原出库行暂存桶下的已拣未发身份，有界键集分页。 */
    java.util.List<Map<String,Object>> shippable(@Param("e") String e,@Param("w") String w,@Param("order") String order,
            @Param("line") String line,@Param("location") String location,@Param("lot") String lot,@Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
