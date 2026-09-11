package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 上架目标库位资格。收货月台/发运位不能当存储上架位。 */
public final class PutawayTargetService {
    public static final String LOCATION_STORAGE = "STORAGE";

    private final SqlSession session;

    public PutawayTargetService(SqlSession session) {
        this.session = session;
    }

    public Map<String, Object> requireStorage(String enterpriseId, String warehouseId, String locationId) {
        Map<String, Object> location = session.getMapper(MasterdataMapper.class)
                .getLocation(enterpriseId, warehouseId, locationId);
        if (location == null) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "上架库位不存在");
        }
        if (!LOCATION_STORAGE.equals(String.valueOf(location.get("location_type")))) {
            throw new InventoryException("INVALID_PUTAWAY_LOCATION", "上架目标必须是存储位");
        }
        return location;
    }
}
