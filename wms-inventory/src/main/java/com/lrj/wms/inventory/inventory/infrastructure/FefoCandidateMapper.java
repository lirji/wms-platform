package com.lrj.wms.inventory.inventory.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** FEFO 候选桶。调用方必须带企业/仓，并在内存中再做效期与门禁资格。 */
public interface FefoCandidateMapper {
    /** listGoodLots：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listGoodLots(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("skuId") String skuId,
            @Param("limit") int limit);
}
