package com.finagent.chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从表格题注里抽单位与币种。
 *
 * <p>为什么用启发式：Docling 只给题注文本，单位是<b>题注里的一句话</b>
 *（真实数据形如「单位：元」）。抽不出来时<b>如实留 null</b>，不要猜 ——
 * 猜错的单位会造成「数值对但比较口径错」（§8.7 的原话）。
 *
 * <p>真实覆盖率：阶段 2 的 345 张表里 70 张有题注。
 */
public record TableMeta(String unit, String currency) {

    private static final Pattern UNIT = Pattern.compile("单位[：:]\\s*([^\\s，,。]{1,16})");

    public static TableMeta fromCaption(String caption) {
        if (caption == null || caption.isBlank()) {
            return new TableMeta(null, null);
        }
        Matcher m = UNIT.matcher(caption);
        if (!m.find()) {
            return new TableMeta(null, null);
        }
        String unit = m.group(1);
        // 只有题注明确提到币种时才填 currency —— 不根据"元"去猜
        String currency = unit.contains("人民币") || unit.equals("元") ? "CNY"
                : unit.contains("美元") ? "USD" : null;
        return new TableMeta(unit, currency);
    }
}
