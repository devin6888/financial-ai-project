package com.finagent.understanding;

import java.util.Locale;
import java.util.Set;

/**
 * 报告类型（doc_type）的取值与中文措辞识别。
 *
 * <p>四个值与 V6 迁移的 CHECK 约束一一对应，且都是 A 股实际会披露的种类：
 * Q1 一季报（1/1~3/31）、SEMI_ANNUAL 半年报（1/1~6/30）、
 * Q3 三季报（7/1~9/30）、ANNUAL 年报（1/1~12/31）。A 股不发单独的四季度报，
 * 四季并进年报，所以四个值正好覆盖全年披露节奏。
 *
 * <p><b>匹配顺序不能反</b>：「半年报」里含有「年报」，「半年度报告」里含有「年度报告」。
 * 先判半年报，再判年报，否则半年报会被识别成年报 —— 而这两者的报告期相差半年，
 * 过滤条件一错，检索会直接落到错误的文档上。
 */
public final class DocTypes {

    public static final String ANNUAL = "ANNUAL";
    public static final String SEMI_ANNUAL = "SEMI_ANNUAL";
    public static final String Q1 = "Q1";
    public static final String Q3 = "Q3";

    public static final Set<String> VALID = Set.of(ANNUAL, SEMI_ANNUAL, Q1, Q3);

    private DocTypes() {
    }

    public static boolean isValid(String docType) {
        return docType != null && VALID.contains(docType);
    }

    /**
     * 从问题里识别报告类型；识别不到返回 {@code null}（= 不限类型）。
     *
     * <p>只认「报告类型」措辞，不从「2024 年」这类年份推算 ——
     * 用户问「2024 年营业收入」时默认指的是年报，但那是<b>默认值偏好</b>不是事实，
     * 由调用方决定要不要用，不在这里偷偷替用户选。
     */
    public static String detect(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (containsAny(q, "半年报", "半年度报告", "中报", "中期报告")) {
            return SEMI_ANNUAL;
        }
        if (containsAny(q, "一季报", "第一季度", "一季度报告", "q1")) {
            return Q1;
        }
        if (containsAny(q, "三季报", "第三季度", "三季度报告", "q3")) {
            return Q3;
        }
        if (containsAny(q, "年报", "年度报告")) {
            return ANNUAL;
        }
        return null;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
