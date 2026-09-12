package com.lrj.wms.inventory.inventory.domain;

import java.util.Set;

/**
 * 库存桶质量、分配策略、门禁命令与决策的稳定编码。
 * 未知码拒绝，不得回落到 GOOD、FIFO 或允许写入。
 */
public final class InventoryCodes {
    public static final String QUALITY_HOLD = "HOLD";
    public static final String QUALITY_GOOD = "GOOD";
    public static final String QUALITY_REJECTED = "REJECTED";

    public static final String ALLOC_FIFO = "FIFO";
    public static final String ALLOC_FEFO = "FEFO";

    public static final String CMD_NEW_RESERVE = "NEW_RESERVE";
    public static final String CMD_NEW_DISPATCH = "NEW_DISPATCH";
    public static final String CMD_NORMAL_MUTATION = "NORMAL_MUTATION";
    public static final String CMD_INFLIGHT_CONFIRM = "INFLIGHT_CONFIRM";
    public static final String CMD_TCC_CANCEL = "TCC_CANCEL";
    public static final String CMD_ARBITRARY_RELEASE = "ARBITRARY_RELEASE";
    public static final String CMD_COUNT_OBSERVE = "COUNT_OBSERVE";
    public static final String CMD_COUNT_ADJUST = "COUNT_ADJUST";
    public static final String CMD_UNFREEZE = "UNFREEZE";
    public static final String CMD_MAINTENANCE = "MAINTENANCE";

    public static final String REASON_RECEIVE = "RECEIVE";
    public static final String REASON_RESERVE = "RESERVE";
    public static final String REASON_RELEASE = "RELEASE";
    public static final String REASON_CONFIRM = "CONFIRM";
    public static final String REASON_MOVE_OUT = "MOVE_OUT";
    public static final String REASON_MOVE_IN = "MOVE_IN";
    public static final String REASON_SHIP = "SHIP";
    public static final String REASON_HOLD = "HOLD";
    public static final String REASON_RELEASE_HOLD = "RELEASE_HOLD";
    public static final String REASON_ADJUST = "ADJUST";

    public static final String AGGREGATE_STOCK_BALANCE = "STOCK_BALANCE";
    public static final String AGGREGATE_RESERVATION = "RESERVATION";
    public static final String EVENT_BALANCE_CHANGED = "InventoryBalanceChanged";
    public static final String EVENT_RESERVATION_CONFIRMED = "ReservationConfirmed";

    public static final String SOURCE_INVENTORY = "wms-inventory";
    public static final String COMMAND_APPLIED = "APPLIED";
    public static final String OUTBOX_PENDING = "PENDING";
    public static final String OUTBOX_CLAIMED = "CLAIMED";
    public static final String OUTBOX_PUBLISHED = "PUBLISHED";
    public static final String OUTBOX_ISOLATED = "ISOLATED";

    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY = "DENY";
    public static final String DECISION_DRAIN = "DRAIN";
    public static final String DECISION_ISOLATE = "ISOLATE";

    private static final Set<String> QUALITIES = Set.of(QUALITY_HOLD, QUALITY_GOOD, QUALITY_REJECTED);
    private static final Set<String> ALLOCATIONS = Set.of(ALLOC_FIFO, ALLOC_FEFO);
    private static final Set<String> COMMANDS = Set.of(CMD_NEW_RESERVE, CMD_NEW_DISPATCH, CMD_NORMAL_MUTATION,
            CMD_INFLIGHT_CONFIRM, CMD_TCC_CANCEL, CMD_ARBITRARY_RELEASE, CMD_COUNT_OBSERVE, CMD_COUNT_ADJUST,
            CMD_UNFREEZE, CMD_MAINTENANCE);
    private static final Set<String> DECISIONS = Set.of(DECISION_ALLOW, DECISION_DENY, DECISION_DRAIN, DECISION_ISOLATE);

    private InventoryCodes() {
    }

    /** 校验质量桶；未知不得当作 GOOD。 */
    public static String requireQuality(String code) {
        return requireKnown("质量状态", QUALITIES, code);
    }

    /** 校验分配策略；FIFO/FEFO 必须显式配置，不得默认。 */
    public static String requireAllocationPolicy(String code) {
        return requireKnown("分配策略", ALLOCATIONS, code);
    }

    /** 校验门禁命令种类。 */
    public static String requireCommand(String code) {
        return requireKnown("库存命令", COMMANDS, code);
    }

    /** 校验门禁决策码。 */
    public static String requireDecision(String code) {
        return requireKnown("门禁决策", DECISIONS, code);
    }

    public static boolean allocatableQuality(String quality) {
        return QUALITY_GOOD.equals(requireQuality(quality));
    }

    private static String requireKnown(String label, Set<String> allowed, String code) {
        if (code == null || !allowed.contains(code)) {
            throw new IllegalArgumentException("未知" + label + "：" + code);
        }
        return code;
    }
}
