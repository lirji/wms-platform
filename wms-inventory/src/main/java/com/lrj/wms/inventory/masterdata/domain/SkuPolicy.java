package com.lrj.wms.inventory.masterdata.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;

/**
 * SKU 数量精度、批次/序列号/效期开关与单位换算。
 * OQ-03 未确认前：不编造生产单位目录，不把日期默认成当日 00:00 UTC。
 */
public final class SkuPolicy {
    private final String skuId;
    private final String enterpriseId;
    private final String code;
    private final String name;
    private final String baseUnit;
    private final int quantityScale;
    private final boolean lotEnabled;
    private final boolean serialEnabled;
    private final boolean expiryEnabled;
    private final long policyVersion;
    private final String state;

    private SkuPolicy(String skuId, String enterpriseId, String code, String name, String baseUnit,
            int quantityScale, boolean lotEnabled, boolean serialEnabled, boolean expiryEnabled,
            long policyVersion, String state) {
        this.skuId = skuId;
        this.enterpriseId = enterpriseId;
        this.code = code;
        this.name = name;
        this.baseUnit = baseUnit;
        this.quantityScale = quantityScale;
        this.lotEnabled = lotEnabled;
        this.serialEnabled = serialEnabled;
        this.expiryEnabled = expiryEnabled;
        this.policyVersion = policyVersion;
        this.state = state;
    }

    /** 创建商品策略；序列号商品基础单位必须为整数精度。 */
    public static SkuPolicy create(String skuId, String enterpriseId, String code, String name, String baseUnit,
            int quantityScale, boolean lotEnabled, boolean serialEnabled, boolean expiryEnabled,
            long policyVersion, String state) {
        if (quantityScale < 0 || quantityScale > 6) {
            throw new IllegalArgumentException("数量精度必须在0到6之间");
        }
        if (serialEnabled && quantityScale != 0) {
            throw new IllegalArgumentException("序列号商品基础单位数量必须为整数");
        }
        if (policyVersion < 0) {
            throw new IllegalArgumentException("策略版本不能为负");
        }
        return new SkuPolicy(
                MasterdataCodes.requireCode("商品标识", skuId),
                MasterdataCodes.requireCode("企业标识", enterpriseId),
                MasterdataCodes.requireCode("商品编码", code),
                MasterdataCodes.requireCode("商品名称", name),
                MasterdataCodes.requireCode("基础单位", baseUnit),
                quantityScale,
                lotEnabled,
                serialEnabled,
                expiryEnabled,
                policyVersion,
                MasterdataCodes.requireResourceState(state));
    }

    /** 把来源单位数量精确换算为基础单位，禁止截断凑数。 */
    public BigDecimal toBaseQuantity(BigDecimal quantity, BigDecimal numerator, BigDecimal denominator) {
        if (quantity == null || quantity.signum() < 0) {
            throw new IllegalArgumentException("数量必须为非负十进制");
        }
        if (numerator == null || denominator == null
                || numerator.signum() <= 0 || denominator.signum() <= 0
                || numerator.stripTrailingZeros().scale() > 0
                || denominator.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("单位换算必须是正整数比");
        }
        try {
            return quantity.multiply(numerator).divide(denominator, quantityScale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("数量无法按单位版本精确换算，禁止截断", ex);
        }
    }

    /** 校验调用方提供的时区是IANA标识；不在这里做日期截止换算。 */
    public static ZoneId requireIanaTimezone(String timezone) {
        try {
            return ZoneId.of(MasterdataCodes.requireCode("时区", timezone));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("时区必须是合法IANA标识：" + timezone, ex);
        }
    }

    /** 无批次SKU的库存桶必须使用 sentinel，且不得当批号写入lot表。 */
    public void requireLotUsage(String lotIdOrCode) {
        if (!lotEnabled) {
            if (!MasterdataCodes.NO_LOT.equals(lotIdOrCode)) {
                throw new IllegalArgumentException("未启用批次的商品只能使用NO_LOT");
            }
            return;
        }
        if (MasterdataCodes.NO_LOT.equals(lotIdOrCode)) {
            throw new IllegalArgumentException("启用批次的商品不能使用NO_LOT sentinel");
        }
        MasterdataCodes.requireCode("批次标识", lotIdOrCode);
    }

    /** 未启用效期时拒绝效期字段；启用时允许源日期暂存，但不默认生成UTC日界。 */
    public void requireExpiryFields(java.time.Instant producedAt, java.time.Instant expiresAt, String sourceDate,
            long expiryRuleVersion) {
        if (expiryRuleVersion < 0) {
            throw new IllegalArgumentException("效期规则版本不能为负");
        }
        if (!expiryEnabled) {
            if (producedAt != null || expiresAt != null || (sourceDate != null && !sourceDate.isBlank())) {
                throw new IllegalArgumentException("未启用效期的商品不得写入生产/失效时间");
            }
            return;
        }
        if (producedAt != null && expiresAt != null && !expiresAt.isAfter(producedAt)) {
            throw new IllegalArgumentException("失效时刻必须晚于生产时刻");
        }
        if (expiresAt == null && (sourceDate == null || sourceDate.isBlank()) && expiryRuleVersion != 0) {
            throw new IllegalArgumentException("缺少失效时刻或源日期时不能绑定未确认的换算规则");
        }
    }

    public String skuId() {
        return skuId;
    }

    public String enterpriseId() {
        return enterpriseId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String baseUnit() {
        return baseUnit;
    }

    public int quantityScale() {
        return quantityScale;
    }

    public boolean lotEnabled() {
        return lotEnabled;
    }

    public boolean serialEnabled() {
        return serialEnabled;
    }

    public boolean expiryEnabled() {
        return expiryEnabled;
    }

    public long policyVersion() {
        return policyVersion;
    }

    public String state() {
        return state;
    }
}
