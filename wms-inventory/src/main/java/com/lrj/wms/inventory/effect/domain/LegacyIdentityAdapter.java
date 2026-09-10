package com.lrj.wms.inventory.effect.domain;

/**
 * 旧客户端适配：只能从不可变事实恢复已有身份，禁止随机生成 effectId 伪装兼容。
 */
public final class LegacyIdentityAdapter {
    public record FactKey(String action, String factType, String factParentId, String factPartId, String factLineId) {
    }

    private LegacyIdentityAdapter() {
    }

    /** 事实不全则隔离，不得分配新的随机身份。 */
    public static FactKey requireRecoverableFacts(String action, String factType, String factParentId, String factPartId,
            String factLineId) {
        if (blank(action) || blank(factType) || blank(factParentId) || blank(factPartId) || blank(factLineId)) {
            throw new EffectProtocolException("EFFECT_IDENTITY_CONFLICT", "旧请求缺少不可变事实，禁止随机生成effectId");
        }
        return new FactKey(EffectCodes.requireAction(action), EffectCodes.requireFactType(factType), factParentId,
                factPartId, factLineId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
