package com.lrj.wms.inventory.effect.domain;

import java.util.Set;

/**
 * 效果与尝试的稳定编码。未知码拒绝，不得回落到可重做或已过账。
 */
public final class EffectCodes {
    public static final String SOURCE_INVENTORY = "wms-inventory";

    public static final String ACTION_RECEIVE = "RECEIVE";
    public static final String ACTION_PUTAWAY = "PUTAWAY";
    public static final String ACTION_PICK = "PICK";
    public static final String ACTION_MOVE = "MOVE";
    public static final String ACTION_SHIP = "SHIP";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_COMPENSATE = "COMPENSATE";
    public static final String ACTION_COUNT_ADJUST = "COUNT_ADJUST";

    public static final String FACT_RECEIPT_PART = "RECEIPT_PART";
    public static final String FACT_SUB_ACTION = "SUB_ACTION";
    public static final String FACT_SHIPMENT_PART = "SHIPMENT_PART";
    public static final String FACT_CASE_PART = "CASE_PART";
    public static final String FACT_ADJUSTMENT_LINE = "ADJUSTMENT_LINE";

    public static final String STATE_REGISTERED = "REGISTERED";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_STARTED = "STARTED";
    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_APPLIED = "APPLIED";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_SAFE_CLOSED = "SAFE_CLOSED";

    private static final Set<String> ACTIONS = Set.of(ACTION_RECEIVE, ACTION_PUTAWAY, ACTION_PICK, ACTION_MOVE,
            ACTION_SHIP, ACTION_RETURN, ACTION_COMPENSATE, ACTION_COUNT_ADJUST);
    private static final Set<String> FACTS = Set.of(FACT_RECEIPT_PART, FACT_SUB_ACTION, FACT_SHIPMENT_PART,
            FACT_CASE_PART, FACT_ADJUSTMENT_LINE);
    private static final Set<String> EFFECT_STATES = Set.of(STATE_REGISTERED, STATE_OPEN, STATE_STARTED, STATE_UNKNOWN,
            STATE_APPLIED, STATE_REJECTED, STATE_CANCELLED, STATE_SAFE_CLOSED);
    private static final Set<String> BLOCKED_REAUTH = Set.of(STATE_STARTED, STATE_UNKNOWN, STATE_OPEN);

    private EffectCodes() {
    }

    /** 校验动作；未知不得当作可登记。 */
    public static String requireAction(String code) {
        return requireKnown("动作", ACTIONS, code);
    }

    /** 校验事实类型。 */
    public static String requireFactType(String code) {
        return requireKnown("事实类型", FACTS, code);
    }

    /** 校验效果状态。 */
    public static String requireEffectState(String code) {
        return requireKnown("效果状态", EFFECT_STATES, code);
    }

    /** 非空标识。 */
    public static String requireId(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    /** STARTED/UNKNOWN/活动OPEN不得直接发下一尝试。 */
    public static boolean blocksNewAttempt(String effectState) {
        return BLOCKED_REAUTH.contains(requireEffectState(effectState));
    }

    /** 仅登记完成或已安全关闭且未过账才允许新尝试。 */
    public static boolean allowsNewAttempt(String effectState) {
        String state = requireEffectState(effectState);
        return STATE_REGISTERED.equals(state) || STATE_SAFE_CLOSED.equals(state);
    }

    private static String requireKnown(String label, Set<String> allowed, String code) {
        if (code == null || !allowed.contains(code)) {
            throw new IllegalArgumentException("未知" + label + "：" + code);
        }
        return code;
    }
}
