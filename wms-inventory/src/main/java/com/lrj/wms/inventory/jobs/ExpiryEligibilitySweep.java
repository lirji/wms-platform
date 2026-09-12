package com.lrj.wms.inventory.jobs;

import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;
import org.apache.seata.core.context.RootContext;

/**
 * 过期批次巡检。只通知，不释放预占、不改 lot、不发明失效时刻。
 */
public final class ExpiryEligibilitySweep {
    public static final int PAGE_LIMIT = 100;

    private final SqlSession session;
    private final Clock clock;

    public ExpiryEligibilitySweep(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    public Report execute(String enterpriseId, String warehouseId, String windowId) {
        RootContext.unbind();
        if (enterpriseId == null || enterpriseId.isBlank() || warehouseId == null || warehouseId.isBlank()
                || windowId == null || windowId.isBlank()) {
            throw new JobRunException("INVALID_SCOPE", "过期巡检必须带企业/仓/窗口");
        }
        ExpiryEligibilityMapper mapper = session.getMapper(ExpiryEligibilityMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        List<Map<String, Object>> lots = mapper.listExpiredLots(enterpriseId, warehouseId, now, PAGE_LIMIT);
        int noticed = 0;
        int openReservations = 0;
        for (Map<String, Object> lot : lots) {
            if (ExpiryPolicy.satisfied(ExpiryPolicy.instantOf(lot.get("expires_at")), clock.instant())) {
                continue;
            }
            String lotId = String.valueOf(lot.get("id"));
            int open = mapper.countOpenReservations(enterpriseId, warehouseId, lotId);
            mapper.upsertNotice(UUID.randomUUID().toString(), enterpriseId, warehouseId, lotId, windowId,
                    Timestamp.from(ExpiryPolicy.instantOf(lot.get("expires_at"))), open, now);
            noticed++;
            openReservations += open;
        }
        return new Report(lots.size(), noticed, openReservations);
    }

    public record Report(int expiredLots, int notices, int openReservations) {
    }
}
