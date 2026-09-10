package com.lrj.wms.inventory.inventory.domain;

import java.util.Set;

/**
 * 仓级预占头状态。部分消费/释放只改明细累计，头状态在剩余量为 0 时才进入终态。
 * 不用枚举序号；未知码不得当作 TRIED。HELD/EXPIRED 不再用于跨仓路径。
 */
public final class ReservationState {
    public static final String TRIED = "TRIED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CONSUMED = "CONSUMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RELEASED = "RELEASED";

    public static final String CAUSE_TCC_CONFIRM = "TCC_CONFIRM";
    public static final String CAUSE_TCC_CANCEL = "TCC_CANCEL";
    public static final String CAUSE_CONSUME_COMPLETE = "CONSUME_COMPLETE";
    public static final String CAUSE_BUSINESS_RELEASE_COMPLETE = "BUSINESS_RELEASE_COMPLETE";

    private static final Set<String> STATES = Set.of(TRIED, CONFIRMED, CONSUMED, CANCELLED, RELEASED);
    private static final Set<String> TERMINAL = Set.of(CONSUMED, CANCELLED, RELEASED);
    private static final Set<String> OCCUPY_RESERVED = Set.of(TRIED, CONFIRMED);

    private ReservationState() {
    }

    /** 校验预占状态。 */
    public static String require(String code) {
        if (code == null || !STATES.contains(code)) {
            throw new IllegalArgumentException("未知预占状态：" + code);
        }
        return code;
    }

    /** TRIED 与 CONFIRMED 计入 reserved；已消费/取消/业务释放不再占用。 */
    public static boolean occupiesReserved(String state) {
        return OCCUPY_RESERVED.contains(require(state));
    }

    public static boolean isTerminal(String state) {
        return TERMINAL.contains(require(state));
    }

    /**
     * 合法迁移；CONFIRMED 后的用户取消走业务释放，不得调用原 TCC Cancel。
     * 部分数量变化保持 CONFIRMED，不在这里发终态。
     */
    public static String requireTransition(String from, String to, String cause) {
        String current = require(from);
        String next = require(to);
        if (CAUSE_TCC_CONFIRM.equals(cause) && TRIED.equals(current) && CONFIRMED.equals(next)) {
            return next;
        }
        if (CAUSE_TCC_CANCEL.equals(cause) && TRIED.equals(current) && CANCELLED.equals(next)) {
            return next;
        }
        if (CAUSE_CONSUME_COMPLETE.equals(cause) && CONFIRMED.equals(current) && CONSUMED.equals(next)) {
            return next;
        }
        if (CAUSE_BUSINESS_RELEASE_COMPLETE.equals(cause) && CONFIRMED.equals(current) && RELEASED.equals(next)) {
            return next;
        }
        throw new IllegalArgumentException("非法预占迁移：" + current + " -> " + next + " / " + cause);
    }
}
