package com.lrj.wms.outbound.seed;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 出库演示单计数。 */
public interface SeedOutboundMapper {
    @Select("SELECT COUNT(*) FROM outbound_order WHERE enterprise_id=#{enterpriseId}")
    int countOrders(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM outbound_line WHERE enterprise_id=#{enterpriseId}")
    int countLines(@Param("enterpriseId") String enterpriseId);
}
