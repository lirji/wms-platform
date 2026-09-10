package com.lrj.wms.inventory.inventory.infrastructure;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存 Outbox 写入。调用方必须带企业/仓条件，与业务同会话提交。 */
public interface OutboxMapper {
    /** 插入待投递事件；本切片不领取。 */
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
}
