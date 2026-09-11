package com.lrj.wms.inventory.inventory.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** FEFO 候选桶。调用方必须带企业/仓，并在内存中再做效期与门禁资格。 */
public interface FefoCandidateMapper {
    @Select("SELECT b.id AS balance_id, b.lot_id, b.location_id, b.owner_id, b.quality_code, b.on_hand_qty, "
            + "b.reserved_qty, b.free_execution_claim_qty, lot.expires_at, loc.location_type, loc.state AS location_state, "
            + "gate.state AS gate_state FROM stock_balance b "
            + "INNER JOIN lot ON lot.enterprise_id=b.enterprise_id AND lot.warehouse_id=b.warehouse_id AND lot.id=b.lot_id "
            + "INNER JOIN location loc ON loc.enterprise_id=b.enterprise_id AND loc.warehouse_id=b.warehouse_id "
            + "AND loc.id=b.location_id "
            + "INNER JOIN location_gate gate ON gate.enterprise_id=b.enterprise_id AND gate.warehouse_id=b.warehouse_id "
            + "AND gate.location_id=b.location_id "
            + "WHERE b.enterprise_id=#{enterpriseId} AND b.warehouse_id=#{warehouseId} AND b.owner_id=#{ownerId} "
            + "AND b.sku_id=#{skuId} AND b.quality_code='GOOD' "
            + "ORDER BY lot.expires_at IS NULL, lot.expires_at, b.lot_id, b.location_id LIMIT #{limit}")
    List<Map<String, Object>> listGoodLots(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("ownerId") String ownerId, @Param("skuId") String skuId,
            @Param("limit") int limit);
}
