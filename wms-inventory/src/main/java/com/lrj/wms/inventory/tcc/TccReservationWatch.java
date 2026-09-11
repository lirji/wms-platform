package com.lrj.wms.inventory.tcc;

import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 仓级预占巡检。只读 TRIED/CONFIRMED，禁止按 TTL 释放或自行 Confirm/Cancel。
 */
public final class TccReservationWatch {
    public static final String HANDLER = "tccReservationWatch";
    public static final int PAGE_LIMIT = 100;

    private final SqlSession session;

    public TccReservationWatch(SqlSession session) {
        this.session = session;
    }

    /** 清理可能泄漏的 XID 后只读巡检。 */
    public Report inspect(String enterpriseId, String warehouseId) {
        RootContext.unbind();
        if (enterpriseId == null || enterpriseId.isBlank() || warehouseId == null || warehouseId.isBlank()) {
            throw new IllegalArgumentException("巡检必须带企业和仓");
        }
        List<Map<String, Object>> rows = session.getMapper(InventoryMapper.class)
                .listWatchReservations(enterpriseId, warehouseId, PAGE_LIMIT);
        int tried = 0;
        int confirmed = 0;
        for (Map<String, Object> row : rows) {
            String state = String.valueOf(row.get("state"));
            if (ReservationState.TRIED.equals(state)) {
                tried++;
            } else if (ReservationState.CONFIRMED.equals(state)) {
                confirmed++;
            }
        }
        return new Report(tried, confirmed, rows.size());
    }

    /** XXL 不得进入二阶段。 */
    public static void refusePhaseTwo() {
        throw new IllegalStateException("XXL不得Confirm或Cancel");
    }

    public record Report(int tried, int confirmed, int watched) {
    }
}
