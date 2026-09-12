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

    int casCancelRequested(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("now") Timestamp now);
}
