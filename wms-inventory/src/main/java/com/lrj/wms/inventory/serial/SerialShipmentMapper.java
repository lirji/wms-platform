package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 不可变原发运与有界恢复进度分离，所有操作固定企业仓。 */
public interface SerialShipmentMapper {
    /** 身份、数量和意图同事务插入，完整性异常必须回滚。 */
    int insert(@Param("row") Map<String,Object> row);
    /** 按原事实定位领取进度。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    /** 有界扫描到期意图，跳过其他执行器持锁行。 */
    Map<String,Object> next(@Param("e") String e,@Param("w") String w,@Param("now") Timestamp now);
    /** 领取增加代际及次数，旧执行器不能续写。 */
    int claim(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("until") Timestamp until,@Param("now") Timestamp now);
    /** 仅当前代际可持久化匹配证明，结果与进度原子提交。 */
    int finish(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("state") String state,
            @Param("error") String error,@Param("result") String result,@Param("next") Timestamp next,@Param("now") Timestamp now);
    /** 只对已核查隔离意图重新授予预算，保留全部原业务字段。 */
    int requeue(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("now") Timestamp now);
    /** 数量已按原预占扣减，身份仍只能从原授权代际离库。 */
    int depart(@Param("row") Map<String,Object> row);
    /** 登记证明不修改数量，只更新仍属于该次离库的本地观察。 */
    int observe(@Param("row") Map<String,Object> row);
}
