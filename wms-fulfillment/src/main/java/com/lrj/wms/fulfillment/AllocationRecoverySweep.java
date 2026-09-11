package com.lrj.wms.fulfillment;

import java.util.List;
import java.util.Map;

/**
 * 同步已观察到的 TC 终态并补齐 ALLOCATED/Outbox。二阶段仍由 TC 负责，本任务不得 Confirm/Cancel。
 */
public final class AllocationRecoverySweep {
    public static final String HANDLER = "allocationRecoverySweep";

    private final FulfillmentService fulfillment;
    private final TcStatusPort tcStatus;

    public AllocationRecoverySweep(FulfillmentService fulfillment, TcStatusPort tcStatus) {
        this.fulfillment = fulfillment;
        this.tcStatus = tcStatus == null ? new UnavailableTcStatusPort() : tcStatus;
    }

    /** 先写观察副本，再复用屏障恢复。端口为空则保持 RECOVERY_PENDING。 */
    public Report execute(String enterpriseId) {
        if (enterpriseId == null || enterpriseId.isBlank()) {
            throw new IllegalArgumentException("恢复扫描必须带企业");
        }
        int observed = 0;
        List<Map<String, Object>> open = fulfillment.listOpenBoundAttempts(enterpriseId);
        for (Map<String, Object> attempt : open) {
            String evidence = attempt.get("tc_terminal_evidence") == null ? ""
                    : String.valueOf(attempt.get("tc_terminal_evidence"));
            if (!evidence.isBlank()) {
                continue;
            }
            String xid = String.valueOf(attempt.get("xid"));
            var observation = tcStatus.read(xid);
            if (observation.isEmpty()) {
                continue;
            }
            fulfillment.observeTc(enterpriseId, String.valueOf(attempt.get("id")), observation.get().status(),
                    observation.get().evidence());
            observed++;
        }
        int recovered = fulfillment.recoverReadyBarriers(enterpriseId);
        return new Report(open.size(), observed, recovered);
    }

    /** XXL 不得进入二阶段。 */
    public static void refusePhaseTwo() {
        throw new IllegalStateException("XXL不得Confirm或Cancel");
    }

    public record Report(int openAttempts, int newlyObserved, int recovered) {
    }
}
