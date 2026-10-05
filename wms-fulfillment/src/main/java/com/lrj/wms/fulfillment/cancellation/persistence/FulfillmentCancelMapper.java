package com.lrj.wms.fulfillment.cancellation.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 履约取消请求；SQL 只在 XML。 */
public interface FulfillmentCancelMapper {
    /** 写入{@code fulfillment_cancellation}，将 SQL 与绑定参数保持在同一持久化入口。唯一约束吸收重试，影响行数用于区分首次写入和重复。 */
    int insertIgnore(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId,
            @Param("clientOperationId") String clientOperationId,
            @Param("attemptId") String attemptId,
            @Param("reason") String reason,
            @Param("actorId") String actorId,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 读取{@code fulfillment_cancellation}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getByKey(
            @Param("enterpriseId") String enterpriseId,
            @Param("clientOperationId") String clientOperationId);

    /** 原尝试的首个取消决定及冻结执行计划，读取不引入反向执行器锁。 */
    Map<String, Object> compensationSource(@Param("e") String e, @Param("a") String a);

    /** 原attempt锁下聚合逐仓结果，全部原参与仓返回才结束补偿。 */
    java.util.List<Map<String, Object>> forOrder(@Param("e") String e, @Param("o") String o);

    /** 写入{@code allocation_attempt}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int bindCanonical(@Param("e") String e, @Param("a") String a, @Param("id") String id);

    /** 写入{@code fulfillment_cancellation}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int startCompensation(@Param("e") String e, @Param("a") String a);

    /** 写入{@code fulfillment_cancellation_result}、{@code attempt_id}，将 SQL 与绑定参数保持在同一持久化入口。 */
    int insertCompensationResult(@Param("r") Map<String, Object> r);

    /** 读取{@code fulfillment_cancellation_result}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> compensationResult(
            @Param("e") String e, @Param("a") String a, @Param("w") String w);

    /** 写入{@code allocation_execution}、{@code fulfillment_cancellation}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finishExecution(@Param("e") String e, @Param("a") String a);

    /** 写入{@code fulfillment_cancellation}、{@code fulfillment_cancellation_result}、{@code allocation_participant}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int finishCompensation(@Param("e") String e, @Param("a") String a);

    /** 写入{@code allocation_attempt}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int casCancelRequested(
            @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId,
            @Param("now") Timestamp now);
}
