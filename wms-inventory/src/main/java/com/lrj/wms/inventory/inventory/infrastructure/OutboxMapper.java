package com.lrj.wms.inventory.inventory.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存 Outbox。写入带企业/仓条件；领取按本物理数据源扫描。 */
public interface OutboxMapper {
    /** 插入待投递事件。 */
    @Insert("INSERT INTO outbox_event (event_id, enterprise_id, warehouse_id, aggregate_type, aggregate_id, aggregate_version, "
            + "event_type, operation_id, payload, status, claim_epoch, lease_until, next_attempt_at, published_at, version, "
            + "created_at, updated_at) VALUES (#{eventId}, #{enterpriseId}, #{warehouseId}, #{aggregateType}, #{aggregateId}, "
            + "#{aggregateVersion}, #{eventType}, #{operationId}, CAST(#{payload} AS JSON), 'PENDING', 0, NULL, #{now}, NULL, 0, "
            + "#{now}, #{now})")
    int insertPending(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
            @Param("eventType") String eventType, @Param("operationId") String operationId, @Param("payload") String payload,
            @Param("now") Timestamp now);

    /** 按操作核对是否已写 Outbox，供重放断言。 */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND operation_id=#{operationId}")
    int countByOperation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("operationId") String operationId);

    /** 锁定本库到期或租约过期事件；SKIP LOCKED 避免多发布器互阻。 */
    @Select("SELECT event_id, enterprise_id, warehouse_id, aggregate_type, aggregate_id, aggregate_version, event_type, "
            + "operation_id, CAST(payload AS CHAR) AS payload, status, claim_epoch, version FROM outbox_event "
            + "WHERE (status='PENDING' AND next_attempt_at<=#{now}) "
            + "OR (status='CLAIMED' AND lease_until IS NOT NULL AND lease_until<#{now}) "
            + "ORDER BY next_attempt_at, event_id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Map<String, Object>> lockDue(@Param("now") Timestamp now, @Param("limit") int limit);

    /** CAS 领取；仅原代际且仍可领取时成功。 */
    @Update("UPDATE outbox_event SET status='CLAIMED', claim_epoch=claim_epoch+1, lease_until=#{leaseUntil}, "
            + "version=version+1, updated_at=#{now} WHERE event_id=#{eventId} AND claim_epoch=#{claimEpoch} "
            + "AND (status='PENDING' OR (status='CLAIMED' AND lease_until<#{now}))")
    int claim(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("leaseUntil") Timestamp leaseUntil,
            @Param("now") Timestamp now);

    /** 投递成功。必须仍持有本次 claim_epoch。 */
    @Update("UPDATE outbox_event SET status='PUBLISHED', published_at=#{now}, lease_until=NULL, next_attempt_at=#{now}, "
            + "version=version+1, updated_at=#{now} WHERE event_id=#{eventId} AND claim_epoch=#{claimEpoch} AND status='CLAIMED'")
    int markPublished(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("now") Timestamp now);

    /** 可重试失败，回到 PENDING 并推迟下次领取。 */
    @Update("UPDATE outbox_event SET status='PENDING', lease_until=NULL, next_attempt_at=#{nextAttemptAt}, "
            + "version=version+1, updated_at=#{now} WHERE event_id=#{eventId} AND claim_epoch=#{claimEpoch} AND status='CLAIMED'")
    int markRetry(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch,
            @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("now") Timestamp now);

    /** 毒消息隔离，阻塞该事件后续自动投递。 */
    @Update("UPDATE outbox_event SET status='ISOLATED', lease_until=NULL, version=version+1, updated_at=#{now} "
            + "WHERE event_id=#{eventId} AND claim_epoch=#{claimEpoch} AND status='CLAIMED'")
    int markIsolated(@Param("eventId") String eventId, @Param("claimEpoch") long claimEpoch, @Param("now") Timestamp now);
}
