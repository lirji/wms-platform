package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 原收货批次身份清单，只允许首次写入和受条件保护的状态更新。 */
public interface SerialReceiptBatchMapper {
    /** 先固定原清单，再与数量和逐身份意图一起提交。 */
    int insert(Map<String,Object> row);
    /** 按企业仓及原RECEIVE命令串行化同批重放。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("command") String command);
    /** 状态随同一事务中的库存命令更新；不修改原清单。 */
    int finish(@Param("e") String e,@Param("w") String w,@Param("command") String command,
            @Param("state") String state,@Param("now") Timestamp now);
}
