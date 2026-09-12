package com.lrj.wms.outbound.seed;

import org.apache.ibatis.annotations.Param;

/** 出库演示单计数。 */
public interface SeedOutboundMapper {
    /** countOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countOrders(@Param("enterpriseId") String enterpriseId);

    /** countLines：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countLines(@Param("enterpriseId") String enterpriseId);
}
