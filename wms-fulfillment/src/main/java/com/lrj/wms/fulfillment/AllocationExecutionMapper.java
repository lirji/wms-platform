package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 执行进度持久化；短事务领取，外部副作用结束后按代际条件更新。 */
public interface AllocationExecutionMapper {
    /** 同attempt或同命令键竞争不覆盖原事实。 */
    int insert(@Param("row") Map<String,Object> row);
    /** 授权命令读取及恢复回写时锁原执行行。 */
    Map<String,Object> lock(@Param("e") String enterprise,@Param("a") String attempt);
    /** 只读状态不领取执行权。 */
    Map<String,Object> status(@Param("e") String enterprise,@Param("a") String attempt);
    /** 每企业每次只锁一项，跳过其他执行器租约。 */
    Map<String,Object> due(@Param("e") String enterprise,@Param("now") Timestamp now);
    /** 同一阶段每次领取新代际；begin调用前必须持久化BEGIN_CALLING。 */
    int claim(@Param("e") String enterprise,@Param("a") String attempt,@Param("epoch") long epoch,
            @Param("lease") Timestamp lease,@Param("now") Timestamp now);
    /** 只持久化当前领取代际的结果，已固定的请求动作及XID不得覆盖。 */
    int save(@Param("row") Map<String,Object> row,@Param("epoch") long epoch,@Param("now") Timestamp now);
}
