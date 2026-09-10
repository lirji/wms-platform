package com.lrj.wms.inventory.effect.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 版本化意图摘要。重放必须用原 digestVersion，不能用部署时的新默认字段凑数。
 */
public final class RequestDigest {
    public static final long VERSION_1 = 1L;
    public static final long VERSION_2 = 2L;

    private RequestDigest() {
    }

    /** 按指定版本规范化并散列；未知版本拒绝。 */
    public static String digest(long version, String action, String factType, String factParentId, String factPartId,
            String factLineId, String quantity, String unit) {
        return sha256(canonical(version, action, factType, factParentId, factPartId, factLineId, quantity, unit));
    }

    /** 保存的规范化原文，供原版本重放。 */
    public static String canonical(long version, String action, String factType, String factParentId, String factPartId,
            String factLineId, String quantity, String unit) {
        if (version < 1) {
            throw new IllegalArgumentException("digestVersion必须从1起");
        }
        if (version == VERSION_1) {
            return required(action, factType, factParentId, factPartId, factLineId);
        }
        if (version == VERSION_2) {
            return required(action, factType, factParentId, factPartId, factLineId)
                    + '\u001f' + blankToEmpty(quantity) + '\u001f' + blankToEmpty(unit).toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("未知digestVersion：" + version);
    }

    private static String required(String action, String factType, String factParentId, String factPartId,
            String factLineId) {
        return EffectCodes.requireAction(action) + '\u001f' + EffectCodes.requireFactType(factType) + '\u001f'
                + EffectCodes.requireId("父事实", factParentId) + '\u001f' + EffectCodes.requireId("分批事实", factPartId)
                + '\u001f' + EffectCodes.requireId("行事实", factLineId);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }
}
