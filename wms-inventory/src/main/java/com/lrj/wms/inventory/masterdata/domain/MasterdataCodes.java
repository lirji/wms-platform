package com.lrj.wms.inventory.masterdata.domain;

import java.util.Set;

/**
 * 主数据稳定编码。持久化必须写这些字符串，禁止依赖枚举序号。
 * 未知编码拒绝，不得回落到开放/启用。
 */
public final class MasterdataCodes {
    /** 无批次库存桶使用的非空 sentinel，不在 lot 表落行。 */
    public static final String NO_LOT = "NO_LOT";

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_DISABLED = "DISABLED";

    public static final String GATE_OPEN = "OPEN";
    public static final String GATE_QUIESCING = "QUIESCING";
    public static final String GATE_FROZEN = "FROZEN";
    public static final String GATE_MAINTENANCE = "MAINTENANCE";

    private static final Set<String> RESOURCE_STATES = Set.of(STATE_ACTIVE, STATE_DISABLED);
    private static final Set<String> GATE_STATES =
            Set.of(GATE_OPEN, GATE_QUIESCING, GATE_FROZEN, GATE_MAINTENANCE);

    private MasterdataCodes() {
    }

    /** 校验仓/库位/SKU 状态；未知码不得当作 ACTIVE。 */
    public static String requireResourceState(String code) {
        return requireKnown("资源状态", RESOURCE_STATES, code);
    }

    /** 校验门禁状态；未知码不得当作 OPEN。 */
    public static String requireGateState(String code) {
        return requireKnown("门禁状态", GATE_STATES, code);
    }

    /** 拒绝空白编码。 */
    public static String requireCode(String label, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return code;
    }

    private static String requireKnown(String label, Set<String> allowed, String code) {
        if (code == null || !allowed.contains(code)) {
            throw new IllegalArgumentException("未知" + label + "：" + code);
        }
        return code;
    }
}
