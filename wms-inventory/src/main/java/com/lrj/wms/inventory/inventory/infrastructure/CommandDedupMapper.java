package com.lrj.wms.inventory.inventory.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存命令幂等。调用方必须带企业/仓条件，与业务同会话提交。 */
public interface CommandDedupMapper {
    /** 首次受理插入；冲突返回 0，不覆盖原摘要。 */
    @Insert("INSERT IGNORE INTO command_dedup (id, enterprise_id, warehouse_id, source_system, action, client_operation_id, "
            + "operation_id, request_digest, digest_version, status, response_json, retain_until, version, created_at, "
            + "updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{sourceSystem}, #{action}, #{clientOperationId}, "
            + "#{operationId}, #{requestDigest}, #{digestVersion}, #{status}, NULL, #{retainUntil}, 0, #{now}, #{now})")
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceSystem") String sourceSystem,
            @Param("action") String action, @Param("clientOperationId") String clientOperationId,
            @Param("operationId") String operationId, @Param("requestDigest") String requestDigest,
            @Param("digestVersion") int digestVersion, @Param("status") String status,
            @Param("retainUntil") Timestamp retainUntil, @Param("now") Timestamp now);

    /** 按客户端键加锁读取，比较摘要后决定重放或冲突。 */
    @Select("SELECT operation_id, request_digest, digest_version, status FROM command_dedup "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND source_system=#{sourceSystem} "
            + "AND action=#{action} AND client_operation_id=#{clientOperationId} FOR UPDATE")
    Map<String, Object> lockByClient(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceSystem") String sourceSystem, @Param("action") String action,
            @Param("clientOperationId") String clientOperationId);
}
