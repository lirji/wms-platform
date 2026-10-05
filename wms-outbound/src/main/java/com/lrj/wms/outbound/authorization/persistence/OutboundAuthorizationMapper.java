package com.lrj.wms.outbound.authorization.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/** 出库执行授权与 TCC 证据副本；SQL 只在 XML。 */
public interface OutboundAuthorizationMapper {
    /** 读取{@code outbound_tcc_evidence}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getEvidence(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId);

    /** 原始消息证据只插入一次，冲突后必须逐字段核对，禁止覆盖旧证据。 */
    int insertEvidence(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId,
            @Param("xid") String xid,
            @Param("evidenceRef") String evidenceRef,
            @Param("hash") String hash,
            @Param("payload") String payload,
            @Param("now") Timestamp now);

    /** 与建单/Inbox同事务锁定完整快照，避免并发重放变更权限。 */
    Map<String, Object> lockEvidence(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("attemptId") String attemptId);

    /** 写入{@code outbound_execution_authorization}，将 SQL 与绑定参数保持在同一持久化入口。唯一约束吸收重试，影响行数用于区分首次写入和重复。 */
    int insertAuthorizationIgnore(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("outboundOrderId") String outboundOrderId,
            @Param("clientOperationId") String clientOperationId,
            @Param("authorizationId") String authorizationId,
            @Param("attemptId") String attemptId,
            @Param("xid") String xid,
            @Param("evidenceRef") String evidenceRef,
            @Param("participantSetHash") String participantSetHash,
            @Param("actorId") String actorId,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 读取{@code outbound_execution_authorization}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getAuthorizationByKey(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    /** 写入{@code outbound_order}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int casBindAuthorization(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId,
            @Param("authorizationId") String authorizationId,
            @Param("now") Timestamp now);

    /** 执行时锁定并核对授权与可信证据，裸字符串不能授予库存作业能力。 */
    String verifiedAuthorization(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId,
            @Param("attemptId") String attemptId,
            @Param("authorizationId") String authorizationId);

    /** 读取{@code outbound_execution_authorization}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getAuthorizationById(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("authorizationId") String authorizationId);
}
