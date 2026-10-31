package com.finagent.fact;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额换算：把「16,067,741,039.51 元」变成「160.68 亿元」。
 *
 * <p>为什么这件事必须由代码做：模型换算会编数（四舍五入到哪一位、万/亿混用都靠运气）。
 * 代码算完作为「已报事实的换算值」注入提示词，模型只许引用、不许自己换。
 *
 * <p>规则（与 ST16 §2.6 一致）：
 * <ul>
 *   <li>|值| ≥ 1 亿 → 亿元，2 位小数，HALF_UP</li>
 *   <li>1000 万 ≤ |值| &lt; 1 亿 → 万元</li>
 *   <li>其余 → 原样「元」</li>
 * </ul>
 */
public final class UnitConverter {

    private static final BigDecimal YI = new BigDecimal("100000000");
    private static final BigDecimal QIAN_WAN = new BigDecimal("10000000");

    private UnitConverter() {
    }

    /**
     * @param value 换算后的数值（单位见 unit）
     * @param unit  亿元 / 万元 / 元
     * @param text  给人和模型看的字面量，如「160.68 亿元」
     */
    public record Converted(BigDecimal value, String unit, String text) {

        public static final Converted NONE = new Converted(null, "元", "");
    }

    public static Converted convert(BigDecimal yuan) {
        if (yuan == null) {
            return Converted.NONE;
        }
        BigDecimal abs = yuan.abs();
        if (abs.compareTo(YI) >= 0) {
            return scaled(yuan, YI, "亿元");
        }
        if (abs.compareTo(QIAN_WAN) >= 0) {
            return scaled(yuan, new BigDecimal("10000"), "万元");
        }
        return new Converted(yuan, "元", plain(yuan) + " 元");
    }

    private static Converted scaled(BigDecimal value, BigDecimal unitValue, String unitName) {
        BigDecimal scaled = value.divide(unitValue, 2, RoundingMode.HALF_UP);
        return new Converted(scaled, unitName, scaled.toPlainString() + " " + unitName);
    }

    /** 百分数：固定 2 位 + %（38.46%）；不要用 BigDecimal 默认的科学计数法。 */
    public static String percent(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 百分点：占比变动专用（4.83 个百分点）。 */
    public static String points(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 个百分点";
    }

    /** 不带千分位的原样输出（去掉无意义的尾随 0）。 */
    public static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
