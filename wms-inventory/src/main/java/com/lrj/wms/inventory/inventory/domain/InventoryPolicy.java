package com.lrj.wms.inventory.inventory.domain;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;

/**
 * 库存不为负、余额占用不变量、非序列号可用量资格与门禁命令矩阵。
 * 冻结/质量/效期是资格约束，不另存一列可改写的可用总数。
 * 序列号可用量必须按合格 serial 计件，禁止用桶公式放行。
 */
public final class InventoryPolicy {
    private InventoryPolicy() {
    }

    /** 余额三量非负，且 reserved+freeClaim 不超过 onHand。 */
    public static void requireBalanceInvariant(Quantity onHand, Quantity reserved, Quantity freeClaim) {
        requireNonNegative("on_hand", onHand);
        requireNonNegative("reserved", reserved);
        requireNonNegative("free_execution_claim", freeClaim);
        if (reserved.plus(freeClaim).compareTo(onHand) > 0) {
            throw new IllegalArgumentException("占用不能超过实物量");
        }
    }

    public static void requireNonNegative(String label, Quantity quantity) {
        if (quantity == null || quantity.isNegative()) {
            throw new IllegalArgumentException(label + "不能为负");
        }
    }

    /** 非序列号可分配量；任一资格失败返回 0，不抛成成功占用。 */
    public static Quantity nonSerialAvailable(Quantity onHand, Quantity reserved, Quantity freeClaim,
            boolean locationAllocable, String quality, boolean expirySatisfied, boolean frozen) {
        requireBalanceInvariant(onHand, reserved, freeClaim);
        if (!locationAllocable || frozen || !expirySatisfied || !InventoryCodes.allocatableQuality(quality)) {
            return Quantity.zero(onHand.scale());
        }
        return onHand.minus(reserved).minus(freeClaim);
    }

    /** 序列号商品不得把桶余量当作可分配件数。 */
    public static void rejectBucketFormulaForSerial() {
        throw new IllegalArgumentException("序列号可用量必须按合格身份计件，不能使用桶余量公式");
    }

    /**
     * 门禁命令决策。未知门禁/命令拒绝。
     * MAINTENANCE 只允许维护令牌命令；QUIESCING 新业务拒绝，在途确认走排空。
     */
    public static String decideGate(String gateState, String command) {
        String gate = MasterdataCodes.requireGateState(gateState);
        String cmd = InventoryCodes.requireCommand(command);
        if (MasterdataCodes.GATE_MAINTENANCE.equals(gate)) {
            return InventoryCodes.CMD_MAINTENANCE.equals(cmd)
                    ? InventoryCodes.DECISION_ALLOW
                    : InventoryCodes.DECISION_DENY;
        }
        if (MasterdataCodes.GATE_OPEN.equals(gate)) {
            return decideOpen(cmd);
        }
        if (MasterdataCodes.GATE_QUIESCING.equals(gate)) {
            return decideQuiescing(cmd);
        }
        return decideFrozen(cmd);
    }

    private static String decideOpen(String command) {
        return switch (command) {
            case InventoryCodes.CMD_NEW_RESERVE, InventoryCodes.CMD_NEW_DISPATCH, InventoryCodes.CMD_NORMAL_MUTATION,
                    InventoryCodes.CMD_INFLIGHT_CONFIRM, InventoryCodes.CMD_TCC_CANCEL, InventoryCodes.CMD_COUNT_OBSERVE ->
                InventoryCodes.DECISION_ALLOW;
            default -> InventoryCodes.DECISION_DENY;
        };
    }

    private static String decideQuiescing(String command) {
        return switch (command) {
            case InventoryCodes.CMD_NEW_RESERVE, InventoryCodes.CMD_NEW_DISPATCH, InventoryCodes.CMD_NORMAL_MUTATION ->
                InventoryCodes.DECISION_DENY;
            case InventoryCodes.CMD_INFLIGHT_CONFIRM -> InventoryCodes.DECISION_DRAIN;
            case InventoryCodes.CMD_TCC_CANCEL, InventoryCodes.CMD_UNFREEZE -> InventoryCodes.DECISION_ALLOW;
            case InventoryCodes.CMD_COUNT_OBSERVE, InventoryCodes.CMD_COUNT_ADJUST, InventoryCodes.CMD_ARBITRARY_RELEASE,
                    InventoryCodes.CMD_MAINTENANCE ->
                InventoryCodes.DECISION_DENY;
            default -> throw new IllegalArgumentException("未知库存命令：" + command);
        };
    }

    private static String decideFrozen(String command) {
        return switch (command) {
            case InventoryCodes.CMD_NEW_RESERVE, InventoryCodes.CMD_NEW_DISPATCH, InventoryCodes.CMD_NORMAL_MUTATION,
                    InventoryCodes.CMD_ARBITRARY_RELEASE, InventoryCodes.CMD_MAINTENANCE ->
                InventoryCodes.DECISION_DENY;
            case InventoryCodes.CMD_INFLIGHT_CONFIRM -> InventoryCodes.DECISION_ISOLATE;
            case InventoryCodes.CMD_TCC_CANCEL, InventoryCodes.CMD_COUNT_OBSERVE, InventoryCodes.CMD_COUNT_ADJUST,
                    InventoryCodes.CMD_UNFREEZE ->
                InventoryCodes.DECISION_ALLOW;
            default -> throw new IllegalArgumentException("未知库存命令：" + command);
        };
    }
}
