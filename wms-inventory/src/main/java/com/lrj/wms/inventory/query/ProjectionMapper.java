package com.lrj.wms.inventory.query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 查询投影读写。条件更新必须带世代与源版本。 */
public interface ProjectionMapper {
    /** insertInboxIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertInboxIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("consumer") String consumer, @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("occurredAt") Timestamp occurredAt,
            @Param("now") Timestamp now);

    /** countInbox：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countInbox(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("consumer") String consumer, @Param("eventId") String eventId);

    /** findInboxVersion：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> findInboxVersion(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("consumer") String consumer, @Param("aggregateId") String aggregateId,
            @Param("aggregateVersion") long aggregateVersion);

    /** insertCheckpointIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertCheckpointIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("name") String name, @Param("now") Timestamp now);

    /** lockCheckpoint：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("name") String name);

    /** casCheckpoint：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("name") String name, @Param("live") long live, @Param("rebuild") Long rebuild,
            @Param("eventId") String eventId, @Param("eventTime") Timestamp eventTime, @Param("version") long version,
            @Param("now") Timestamp now);

    /** lockView：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("balanceId") String balanceId);

    /** insertView：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertView(@Param("id") String id, @Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("ownerId") String ownerId, @Param("locationId") String locationId,
            @Param("skuId") String skuId, @Param("lotId") String lotId, @Param("qualityCode") String qualityCode,
            @Param("onHand") BigDecimal onHand, @Param("reserved") BigDecimal reserved, @Param("claim") BigDecimal claim,
            @Param("sourceVersion") long sourceVersion, @Param("asOf") Timestamp asOf, @Param("now") Timestamp now);

    /** casView：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("id") String id, @Param("onHand") BigDecimal onHand,
            @Param("reserved") BigDecimal reserved, @Param("sourceVersion") long sourceVersion,
            @Param("expectedVersion") long expectedVersion, @Param("asOf") Timestamp asOf, @Param("now") Timestamp now);

    /** copyBalances：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int copyBalances(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("now") Timestamp now);

    /** listView：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listView(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation, @Param("skuId") String skuId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** maxAsOf：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Object maxAsOf(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation);

    /** highWater：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Long highWater(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("generation") long generation);
}
