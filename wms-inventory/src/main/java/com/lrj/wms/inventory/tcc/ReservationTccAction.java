package com.lrj.wms.inventory.tcc;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.ReservationLineInput;
import java.util.List;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * 仓级库存 RM 的预占 TCC 动作。业务方法不含 Fence 调用；Fence 由 useTCCFence 或同库 prepare/commit/rollback 包裹。
 * 本类不连接 TC，也不启用 AT 数据源代理。
 */
@LocalTCC
public final class ReservationTccAction {
    public static final String ACTION_NAME = "ReservationTccAction";

    private final InventoryApplicationService inventory;

    public ReservationTccAction(InventoryApplicationService inventory) {
        this.inventory = inventory;
    }

    /**
     * Try：预留库存并落 TRIED。参与者/数量在调用前已冻结，这里不再改仓。
     */
    @TwoPhaseBusinessAction(name = ACTION_NAME, commitMethod = "confirmReserve", rollbackMethod = "cancelReserve",
            useTCCFence = true)
    public boolean tryReserve(BusinessActionContext context,
            @BusinessActionContextParameter("enterpriseId") String enterpriseId,
            @BusinessActionContextParameter("warehouseId") String warehouseId,
            @BusinessActionContextParameter("allocationId") String allocationId,
            @BusinessActionContextParameter("attemptId") String attemptId,
            @BusinessActionContextParameter("requestDigest") String requestDigest,
            @BusinessActionContextParameter("routeEpoch") long routeEpoch,
            List<ReservationLineInput> lines) {
        requireContext(context);
        if (context.getActionContext() == null) {
            context.setActionContext(new java.util.HashMap<>());
        }
        context.addActionContext("enterpriseId", enterpriseId);
        context.addActionContext("warehouseId", warehouseId);
        context.addActionContext("allocationId", allocationId);
        context.addActionContext("attemptId", attemptId);
        context.addActionContext("requestDigest", requestDigest);
        context.addActionContext("routeEpoch", routeEpoch);
        String operationId = phaseOperation("try", context);
        inventory.reserveTried(enterpriseId, warehouseId, operationId, allocationId, ACTION_NAME, allocationId, attemptId,
                context.getXid(), context.getBranchId(), ACTION_NAME, routeEpoch, requestDigest, lines);
        return true;
    }

    /** Confirm：只把原 TRIED 转为 CONFIRMED，不再竞争可分配量。 */
    public boolean confirmReserve(BusinessActionContext context) {
        requireContext(context);
        String operationId = phaseOperation("confirm", context);
        inventory.confirmTried(text(context, "enterpriseId"), text(context, "warehouseId"), operationId,
                text(context, "allocationId"), ACTION_NAME, text(context, "allocationId"), text(context, "attemptId"),
                context.getXid(), context.getBranchId(), ACTION_NAME);
        return true;
    }

    /** Cancel：释放仍 TRIED 的占用；空回滚由 Fence 拦截后业务侧也安全。 */
    public boolean cancelReserve(BusinessActionContext context) {
        requireContext(context);
        String operationId = phaseOperation("cancel", context);
        inventory.cancelTried(text(context, "enterpriseId"), text(context, "warehouseId"), operationId,
                text(context, "allocationId"), ACTION_NAME, text(context, "allocationId"), text(context, "attemptId"),
                context.getXid(), context.getBranchId(), ACTION_NAME);
        return true;
    }

    private static void requireContext(BusinessActionContext context) {
        if (context == null || context.getXid() == null || context.getXid().isBlank()) {
            throw new InventoryException("XID_NOT_BOUND", "TCC 上下文缺少 XID");
        }
    }

    private static String phaseOperation(String phase, BusinessActionContext context) {
        return "tcc-" + phase + ":" + context.getXid() + ":" + context.getBranchId();
    }

    private static String text(BusinessActionContext context, String key) {
        Object value = context.getActionContext(key);
        if (value == null || value.toString().isBlank()) {
            throw new InventoryException("RESOURCE_NOT_FOUND", "TCC 上下文缺少 " + key);
        }
        return value.toString();
    }
}
