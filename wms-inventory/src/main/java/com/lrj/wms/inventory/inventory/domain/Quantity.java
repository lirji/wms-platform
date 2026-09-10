package com.lrj.wms.inventory.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 库存数量：API 十进制字符串，存储 DECIMAL(20,6)，精度由 SKU 显式规定。
 * 禁止截断凑数；未知精度不得回落到 2 位或任意舍入。
 */
public final class Quantity implements Comparable<Quantity> {
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 6;
    /** DECIMAL(20,6) 整数部分最多 14 位。 */
    private static final BigDecimal MAX_ABS = new BigDecimal("100000000000000");
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final BigDecimal amount;
    private final int scale;

    private Quantity(BigDecimal amount, int scale) {
        this.amount = amount;
        this.scale = scale;
    }

    /** 按 SKU 允许精度构造；超出位数拒绝，不四舍五入。 */
    public static Quantity of(BigDecimal raw, int scale) {
        requireScale(scale);
        if (raw == null) {
            throw new IllegalArgumentException("数量不能为空");
        }
        BigDecimal normalized;
        try {
            normalized = raw.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("数量精度超过允许的" + scale + "位，禁止截断", ex);
        }
        if (normalized.abs().compareTo(MAX_ABS) >= 0) {
            throw new IllegalArgumentException("数量超出DECIMAL(20,6)范围");
        }
        return new Quantity(normalized, scale);
    }

    /** 解析契约十进制字符串；拒绝科学计数法。 */
    public static Quantity parse(String text, int scale) {
        if (text == null || !DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException("数量必须是十进制字符串：" + text);
        }
        return of(new BigDecimal(text), scale);
    }

    public static Quantity zero(int scale) {
        return of(BigDecimal.ZERO, scale);
    }

    public Quantity plus(Quantity other) {
        requireSameScale(other);
        return of(amount.add(other.amount), scale);
    }

    public Quantity minus(Quantity other) {
        requireSameScale(other);
        return of(amount.subtract(other.amount), scale);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public int scale() {
        return scale;
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    /** 契约 JSON 使用固定精度的十进制字符串，不用 JSON number。 */
    public String toPlainString() {
        return amount.toPlainString();
    }

    @Override
    public int compareTo(Quantity other) {
        requireSameScale(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Quantity other)) {
            return false;
        }
        return scale == other.scale && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), scale);
    }

    @Override
    public String toString() {
        return toPlainString();
    }

    public static int requireScale(int scale) {
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("数量精度必须在0到6之间：" + scale);
        }
        return scale;
    }

    private void requireSameScale(Quantity other) {
        if (other == null) {
            throw new IllegalArgumentException("数量不能为空");
        }
        if (scale != other.scale) {
            throw new IllegalArgumentException("数量精度必须一致：" + scale + " vs " + other.scale);
        }
    }
}
