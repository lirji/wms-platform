package com.lrj.wms.inventory.inventory.application;

import com.lrj.wms.inventory.inventory.domain.InventoryException;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;

import org.apache.ibatis.session.SqlSession;

import java.util.Map;

/** 上架目标库位资格。收货月台/发运位不能当存储上架位。 */
public final class PutawayTargetService {
    public static final String LOCATION_STORAGE = "STORAGE";

    private final SqlSession session;

    /** 显式接收 PutawayTargetService 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public PutawayTargetService(SqlSession session) {
        this.session = session;
    }

    /** 核对库位存在且允许存储，不能将不可用库位选作上架目标。 */
    public Map<String, Object> requireStorage(
            String enterpriseId, String warehouseId, String locationId) {
        Map<String, Object> location =
                session.getMapper(MasterdataMapper.class)
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
