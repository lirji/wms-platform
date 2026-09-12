package com.lrj.wms.inventory.query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 查询投影读写。条件更新必须带世代与源版本。 */
public interface ProjectionMapper {
    @Insert("INSERT INTO projection_inbox (id, enterprise_id, warehouse_id, consumer_name, event_id, aggregate_id, "
            + "aggregate_version, event_type, payload, occurred_at, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{consumer}, #{eventId}, #{aggregateId}, #{aggregateVersion}, "
            + "#{eventType}, #{payload}, #{occurredAt}, 0, #{now}, #{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertInboxIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("consumer") String consumer, @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("occurredAt") Timestamp occurredAt,
            @Param("now") Timestamp now);

    @Select("SELECT COUNT(*) FROM projection_inbox WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND consumer_name=#{consumer} AND event_id=#{eventId}")
    int countInbox(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("consumer") String consumer, @Param("eventId") String eventId);

    @Select("SELECT event_id, aggregate_id, aggregate_version, event_type, payload, occurred_at FROM projection_inbox "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND consumer_name=#{consumer} "
            + "AND aggregate_id=#{aggregateId} AND aggregate_version=#{aggregateVersion}")
    Map<String, Object> findInboxVersion(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("consumer") String consumer, @Param("aggregateId") String aggregateId,
            @Param("aggregateVersion") long aggregateVersion);

    @Insert("INSERT INTO projection_checkpoint (id, enterprise_id, warehouse_id, projection_name, live_generation, "
            + "rebuild_generation, last_event_id, last_event_time, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{name}, 0, NULL, NULL, NULL, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertCheckpointIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("name") String name, @Param("now") Timestamp now);

    @Select("SELECT id, live_generation, rebuild_generation, last_event_id, last_event_time, version "
            + "FROM projection_checkpoint WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND projection_name=#{name} FOR UPDATE")
    Map<String, Object> lockCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("name") String name);

    @Update("UPDATE projection_checkpoint SET live_generation=#{live}, rebuild_generation=#{rebuild}, "
            + "last_event_id=#{eventId}, last_event_time=#{eventTime}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND projection_name=#{name} "
            + "AND version=#{version}")
    int casCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("name") String name, @Param("live") long live, @Param("rebuild") Long rebuild,
            @Param("eventId") String eventId, @Param("eventTime") Timestamp eventTime, @Param("version") long version,
            @Param("now") Timestamp now);

    @Select("SELECT id, source_version, on_hand_qty, reserved_qty, as_of FROM inventory_view "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND generation=#{generation} "
            + "AND id=#{balanceId} FOR UPDATE")
    Map<String, Object> lockView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("balanceId") String balanceId);

    @Insert("INSERT INTO inventory_view (id, enterprise_id, warehouse_id, generation, owner_id, location_id, sku_id, "
            + "lot_id, quality_code, on_hand_qty, reserved_qty, free_execution_claim_qty, source_version, as_of, "
            + "eligible, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{generation}, "
            + "#{ownerId}, #{locationId}, #{skuId}, #{lotId}, #{qualityCode}, #{onHand}, #{reserved}, #{claim}, "
            + "#{sourceVersion}, #{asOf}, 1, 0, #{now}, #{now})")
    int insertView(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("onHand") BigDecimal onHand, @Param("reserved") BigDecimal reserved, @Param("claim") BigDecimal claim,
            @Param("sourceVersion") long sourceVersion, @Param("asOf") Timestamp asOf, @Param("now") Timestamp now);

    @Update("UPDATE inventory_view SET on_hand_qty=#{onHand}, reserved_qty=#{reserved}, source_version=#{sourceVersion}, "
            + "as_of=#{asOf}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND generation=#{generation} "
            + "AND id=#{id} AND source_version=#{expectedVersion}")
    int casView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("id") String id, @Param("onHand") BigDecimal onHand,
            @Param("reserved") BigDecimal reserved, @Param("sourceVersion") long sourceVersion,
            @Param("expectedVersion") long expectedVersion, @Param("asOf") Timestamp asOf, @Param("now") Timestamp now);

    @Insert("INSERT INTO inventory_view (id, enterprise_id, warehouse_id, generation, owner_id, location_id, sku_id, "
            + "lot_id, quality_code, on_hand_qty, reserved_qty, free_execution_claim_qty, source_version, as_of, "
            + "eligible, version, created_at, updated_at) "
            + "SELECT id, enterprise_id, warehouse_id, #{generation}, owner_id, location_id, sku_id, lot_id, quality_code, "
            + "on_hand_qty, reserved_qty, free_execution_claim_qty, version, #{now}, 1, 0, #{now}, #{now} "
            + "FROM stock_balance WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId}")
    int copyBalances(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("now") Timestamp now);

    @Select("SELECT id, owner_id, location_id, sku_id, lot_id, quality_code, on_hand_qty, reserved_qty, "
            + "free_execution_claim_qty, source_version, as_of, version FROM inventory_view "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND generation=#{generation} "
            + "AND (#{skuId} IS NULL OR sku_id=#{skuId}) ORDER BY sku_id, lot_id, id LIMIT #{limit}")
    List<Map<String, Object>> listView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("skuId") String skuId, @Param("limit") int limit);

    @Select("SELECT MAX(as_of) AS as_of FROM inventory_view WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND generation=#{generation}")
    Object maxAsOf(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation);

    @Select("SELECT MAX(source_version) AS high_water FROM inventory_view WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND generation=#{generation}")
    Long highWater(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation);
}
