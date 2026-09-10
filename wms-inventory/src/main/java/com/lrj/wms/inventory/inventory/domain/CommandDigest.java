package com.lrj.wms.inventory.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 库存命令摘要。重放必须用保存的 digestVersion，不能用新默认字段凑数。 */
public final class CommandDigest {
    public static final int VERSION_1 = 1;

    private CommandDigest() {
    }

    /** v1：任意稳定字段，顺序即规范。 */
    public static String v1Parts(String action, String... parts) {
        StringBuilder canonical = new StringBuilder(action);
        for (String part : parts) {
            canonical.append('\u001f').append(part == null ? "" : part);
        }
        return sha256(canonical.toString());
    }

    /** v1：动作、单据、桶维度、数量及附加身份。 */
    public static String v1(String action, String documentId, StockBucketKey bucket, String quantity, String... extras) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(action).append('\u001f').append(documentId).append('\u001f')
                .append(bucket.enterpriseId()).append('\u001f').append(bucket.warehouseId()).append('\u001f')
                .append(bucket.ownerId()).append('\u001f').append(bucket.locationId()).append('\u001f')
                .append(bucket.skuId()).append('\u001f').append(bucket.lotId()).append('\u001f')
                .append(bucket.qualityCode()).append('\u001f').append(quantity);
        for (String extra : extras) {
            canonical.append('\u001f').append(extra == null ? "" : extra);
        }
        return sha256(canonical.toString());
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
