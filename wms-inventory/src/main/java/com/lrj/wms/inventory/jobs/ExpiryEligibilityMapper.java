package com.lrj.wms.inventory.jobs;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 过期批次巡检。只读 lot/reservation，通知行幂等写入。 */
public interface ExpiryEligibilityMapper {
    /** listExpiredLots：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listExpiredLots(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("now") Timestamp now, @Param("limit") int limit);

    /** countOpenReservations：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countOpenReservations(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId);

    /** upsertNotice：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int upsertNotice(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lotId") String lotId, @Param("windowId") String windowId,
            @Param("expiresAt") Timestamp expiresAt, @Param("openReservations") int openReservations,
            @Param("now") Timestamp now);

    /** countNotice：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countNotice(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lotId") String lotId, @Param("windowId") String windowId);
}
