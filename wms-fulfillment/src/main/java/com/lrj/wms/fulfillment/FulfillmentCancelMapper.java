package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 履约取消请求；SQL 只在 XML。 */
public interface FulfillmentCancelMapper {
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("clientOperationId") String clientOperationId,
            @Param("attemptId") String attemptId, @Param("reason") String reason, @Param("actorId") String actorId,
            @Param("state") String state, @Param("now") Timestamp now);

    Map<String, Object> getByKey(@Param("enterpriseId") String enterpriseId,
            @Param("clientOperationId") String clientOperationId);

    /** 原尝试的首个取消决定及冻结执行计划，读取不引入反向执行器锁。 */
    Map<String,Object> compensationSource(@Param("e") String e,@Param("a") String a);

    /** 原attempt锁下聚合逐仓结果，全部原参与仓返回才结束补偿。 */
    java.util.List<Map<String,Object>> forOrder(@Param("e") String e,@Param("o") String o);
    int bindCanonical(@Param("e") String e,@Param("a") String a,@Param("id") String id);
    int startCompensation(@Param("e") String e,@Param("a") String a);
    int insertCompensationResult(@Param("r") Map<String,Object> r);
    Map<String,Object> compensationResult(@Param("e") String e,@Param("a") String a,@Param("w") String w);
    int finishExecution(@Param("e") String e,@Param("a") String a);
    int finishCompensation(@Param("e") String e,@Param("a") String a);

    int casCancelRequested(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("now") Timestamp now);
}
