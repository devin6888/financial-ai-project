package com.finagent.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 「这个问题根本不是问年报」的判定（阶段 22）。
 *
 * <p>起因：用户问「答案后面的【数值】表格是不是程序拼的」，系统却弹了审批卡，因为作用域里有两份年报、
 * 问题里没有 4 位年份 —— 旧判定只看「缺不缺年份」，不看「这是不是文档问题」。
 *
 * <p><b>宁可放过，不可错杀</b>：只有同时满足「没有任何财务信号（指标/公司/年份）」「没有任何文档词」
 * 而且「命中对话/界面类用词」时才判为非文档问题。像「经营情况怎么样」这种没有明确指标的问题<b>不</b>判，
 * 它仍然走正常检索（可能答不上来，但那是证据问题，不是话题问题）。
 *
 * <p>判定用规则而不是模型：它发生在 understand 节点里，多加一次模型调用会让每轮多 3~4 秒，
 * 而这里要拦的是一眼能看出来的「问系统本身」的话。
 */
public final class OffTopicDetector {

    /** 写进 {@code refuseReason} 的固定前缀；{@code respondInsufficient} 按它切换成能力边界文案（契约 C6）。 */
    public static final String REASON_PREFIX = "这不是关于年报的问题";

    /** 4 位年份：有年份就说明用户在问某个期间，不属于「问系统」。 */
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");

    /** 文档词：出现任何一个都说明话题在文档上。 */
    private static final Pattern DOC_WORDS = Pattern.compile("年报|报告|财报|文档|披露|季报|半年报|页面引用|第\\s*\\d+\\s*页");

    /** 对话/界面类用词：这些词指的是「系统本身」，不是年报内容。 */
    private static final Pattern MARKERS = Pattern.compile(
            "你|您|刚才|上一轮|上一次|这个功能|这个页面|这个按钮|界面|按钮|弹窗|审批|日志|系统|程序|代码|接口|数据库|部署|"
                    + "你是谁|你好|谢谢|怎么用|为什么没有|怎么没有");

    private OffTopicDetector() {
    }

    /**
     * @param metrics          QU 抽出的指标；非空就不算跑题（「净利润是多少」没有年份但仍是财报问题）
     * @param companyMentions  问题里的公司名/代码；非空同理
     * @param yearRange        QU 抽出的年份范围；非空同理
     */
    public static boolean isOffTopic(String question, List<String> metrics,
                                     List<String> companyMentions, int[] yearRange) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            return false;   // 空问题走「拒答」那条老路，不在这里判
        }
        if (metrics != null && !metrics.isEmpty()) {
            return false;
        }
        if (companyMentions != null && !companyMentions.isEmpty()) {
            return false;
        }
        if (yearRange != null && yearRange.length >= 2 && (yearRange[0] > 0 || yearRange[1] > 0)) {
            return false;
        }
        if (YEAR.matcher(text).find() || DOC_WORDS.matcher(text).find()) {
            return false;
        }
        return MARKERS.matcher(text).find();
    }

    public static String reason() {
        return REASON_PREFIX + "：我只能基于已入库的年报内容回答（指标、期间、公司、对比）。"
                + "如果你问的是系统怎么实现，可以直接看 docs/ 下的说明。";
    }
}
