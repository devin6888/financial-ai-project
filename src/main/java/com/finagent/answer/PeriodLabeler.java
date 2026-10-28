package com.finagent.answer;

import com.finagent.citation.Citation;
import com.finagent.scope.DocMeta;
import com.finagent.scope.ResolvedScope;
import com.finagent.understanding.QueryUnderstanding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 期间标注：答案里的数字如果来自「别的年份的报告」，必须留痕。
 *
 * <p>典型场景（阶段 5 端到端实测 Q13）：问 2023 年营业总收入、作用域只有 2024 年报，
 * 系统会从 2024 年报的**上年同期对比列**里取出 2023 的数。数值是对的，
 * 但「证据属于 2024 年报告」这件事不标出来，用户就没法判断这个数是怎么来的，
 * 也没法追溯到底哪份文件是原始出处。
 *
 * <p><b>不改数字、不改引用</b>：只加 warning 与 note。改数字是替会计做判断，
 * 不是检索系统该干的事。
 */
@Component
public class PeriodLabeler {

    public static final String WARNING_PREFIX = "evidence_period_mismatch:";

    public Result label(QueryUnderstanding qu, List<Citation> citations, ResolvedScope scope) {
        Integer questionYear = singleYear(qu);
        if (questionYear == null || citations == null || citations.isEmpty() || scope == null) {
            return Result.EMPTY;
        }
        Set<Integer> mismatched = new LinkedHashSet<>();
        for (Citation c : citations) {
            DocMeta doc = scope.document(c.documentId());
            if (doc != null && doc.docYear() != null && !doc.docYear().equals(questionYear)) {
                mismatched.add(doc.docYear());
            }
        }
        if (mismatched.isEmpty()) {
            return Result.EMPTY;
        }
        List<String> warnings = new ArrayList<>();
        StringBuilder years = new StringBuilder();
        for (Integer year : mismatched) {
            warnings.add(WARNING_PREFIX + questionYear + "@" + year);
            if (!years.isEmpty()) {
                years.append("、");
            }
            years.append(year);
        }
        String note = "部分证据来自 " + years + " 年年度报告的上年同期对比列，数值口径请核对期间。";
        return new Result(List.copyOf(warnings), note);
    }

    private static Integer singleYear(QueryUnderstanding qu) {
        if (qu == null || qu.yearRange() == null || qu.yearRange().length != 2) {
            return null;
        }
        return qu.yearRange()[0] == qu.yearRange()[1] ? qu.yearRange()[0] : null;
    }

    public record Result(List<String> warnings, String note) {
        public static final Result EMPTY = new Result(List.of(), null);
    }
}
