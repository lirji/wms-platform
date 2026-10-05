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

    /** 沿用 SKU 精度创建零数量，避免零值绕过精度约束。 */
    public static Quantity zero(int scale) {
        return of(BigDecimal.ZERO, scale);
    }

    /** 只允许相同 SKU 精度相加，结果仍须满足数据库数量范围。 */
    public Quantity plus(Quantity other) {
        requireSameScale(other);
        return of(amount.add(other.amount), scale);
    }

    /** 只允许相同 SKU 精度相减，结果仍须满足数据库数量范围。 */
    public Quantity minus(Quantity other) {
        requireSameScale(other);
        return of(amount.subtract(other.amount), scale);
    }

    /** 显式判断负数，供业务规则拒绝不合法库存结果。 */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** 按数值判断零量，不依赖 BigDecimal 的表示精度。 */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** 按数值判断正量，供调用方选择有效数量分支。 */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** 返回创建时绑定的 SKU 精度，不能用默认舍入规则替代。 */
    public int scale() {
        return scale;
    }

    /** 返回不可变的精确数值，持久化时避免浮点换算。 */
    public BigDecimal toBigDecimal() {
        return amount;
    }

    /** 契约 JSON 使用固定精度的十进制字符串，不用 JSON number。 */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /** 沿用值对象的确定比较规则，确保排序和业务比较使用相同语义。 */
    @Override
    public int compareTo(Quantity other) {
        requireSameScale(other);
        return amount.compareTo(other.amount);
    }

    /** 以业务身份与数值定义相等，避免对象实例身份影响去重。 */
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

    /** 哈希规则与相等语义保持一致，避免集合去重产生分歧。 */
    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), scale);
    }

    /** 输出固定精度的十进制字符串，诊断和契约数量表示保持一致。 */
    @Override
    public String toString() {
        return toPlainString();
    }

    /** 在数量入口校验数据库允许精度，不能静默截断。 */
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
