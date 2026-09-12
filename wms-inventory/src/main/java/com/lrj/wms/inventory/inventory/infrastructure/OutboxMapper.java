package com.lrj.wms.inventory.inventory.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存 Outbox。写入带企业/仓条件；领取按本物理数据源扫描。 */
public interface OutboxMapper {
    /** 同一命令终态产生稳定回执事件；重复消息不能重复创建回执。 */
    int insertCommandResult(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId,
            @Param("payload") String payload, @Param("now") Timestamp now);

    /** 插入待投递事件。 */
    /** insertPending：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPending(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
            @Param("eventType") String eventType, @Param("operationId") String operationId, @Param("payload") String payload,
            @Param("now") Timestamp now);

    /** 按操作核对是否已写 Outbox，供重放断言。 */
    /** countByOperation：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countByOperation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId);

    /** 锁定本库到期或租约过期事件；SKIP LOCKED 避免多发布器互阻。 */
    /** lockDue：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> lockDue(@Param("now") Timestamp now, @Param("limit") int limit);

    /** CAS 领取；仅原代际且仍可领取时成功。 */
    /** claim：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int claim(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("leaseUntil") Timestamp leaseUntil,
            @Param("now") Timestamp now);

    /** 投递成功。必须仍持有本次 claim_epoch。 */
    /** markPublished：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markPublished(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("now") Timestamp now);

    /** 可重试失败，回到 PENDING 并推迟下次领取。 */
    /** markRetry：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markRetry(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch,
            @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("now") Timestamp now);

    /** 毒消息隔离，阻塞该事件后续自动投递。 */
    /** markIsolated：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markIsolated(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("now") Timestamp now);
}
