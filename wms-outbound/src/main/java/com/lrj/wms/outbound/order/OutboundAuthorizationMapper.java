package com.lrj.wms.outbound.order;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 出库执行授权与 TCC 证据副本；SQL 只在 XML。 */
public interface OutboundAuthorizationMapper {
    Map<String, Object> getEvidence(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId);

    /** 原始消息证据只插入一次，冲突后必须逐字段核对，禁止覆盖旧证据。 */
    int insertEvidence(@Param("id") String id,@Param("enterpriseId") String enterpriseId,@Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId,@Param("xid") String xid,@Param("evidenceRef") String evidenceRef,
            @Param("hash") String hash,@Param("payload") String payload,@Param("now") Timestamp now);
    /** 与建单/Inbox同事务锁定完整快照，避免并发重放变更权限。 */
    Map<String,Object> lockEvidence(@Param("enterpriseId") String enterpriseId,@Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId);

    int insertAuthorizationIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("outboundOrderId") String outboundOrderId,
            @Param("clientOperationId") String clientOperationId, @Param("authorizationId") String authorizationId,
            @Param("attemptId") String attemptId, @Param("xid") String xid,
            @Param("evidenceRef") String evidenceRef, @Param("participantSetHash") String participantSetHash,
            @Param("actorId") String actorId, @Param("state") String state, @Param("now") Timestamp now);

    Map<String, Object> getAuthorizationByKey(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId);

    int casBindAuthorization(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("authorizationId") String authorizationId,
            @Param("now") Timestamp now);
    /** 执行时锁定并核对授权与可信证据，裸字符串不能授予库存作业能力。 */
    String verifiedAuthorization(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("attemptId") String attemptId, @Param("authorizationId") String authorizationId);

    Map<String, Object> getAuthorizationById(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("authorizationId") String authorizationId);
}
