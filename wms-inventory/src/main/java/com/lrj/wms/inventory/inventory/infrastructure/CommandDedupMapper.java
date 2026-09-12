package com.lrj.wms.inventory.inventory.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存命令幂等。调用方必须带企业/仓条件，与业务同会话提交。 */
public interface CommandDedupMapper {
    /** 首次受理插入；冲突返回 0，不覆盖原摘要。 */
    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceSystem") String sourceSystem,
            @Param("action") String action, @Param("clientOperationId") String clientOperationId,
            @Param("operationId") String operationId, @Param("requestDigest") String requestDigest,
            @Param("digestVersion") int digestVersion, @Param("status") String status,
            @Param("retainUntil") Timestamp retainUntil, @Param("now") Timestamp now);

    /** 按客户端键加锁读取，比较摘要后决定重放或冲突。 */
    /** lockByClient：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockByClient(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceSystem") String sourceSystem, @Param("action") String action,
            @Param("clientOperationId") String clientOperationId);
}
