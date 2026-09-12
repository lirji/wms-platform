package com.lrj.wms.runtime.messaging.persistence;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 仅入出库自身的同构来源协议表；不跨库查询订单或库存。调用方持有T1事务。 */
public interface SourceContextMapper {
    /** 锁定本库命令的原始正文，重放不能覆盖第一次的维度或关联ID。 */
    Map<String, Object> lockCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId);
    /** 仅首次T1可以补全正文，完整正文和待投递事件必须一同提交。 */
    int bindCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("payload") String payload);
    /** 只有待发布事件允许写入原始上下文；无法对应唯一事件时回滚整个T1。 */
    int bindOutbox(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("payload") String payload);
}
