package com.finagent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显式命令的规则解析（纯函数：无 Spring、无 IO、无模型调用）。
 *
 * <p>为什么只做规则、不做模型推断（ST9 D2）：写错一条偏好会污染之后<b>每一轮</b>回答，
 * 而「模型判断用户在不在下指令」的准确率无法离线评估。规则宁可漏（漏了用户用
 * POST /api/memories 显式写），也不误写。
 *
 * <p>匹配只在<b>句首</b>（或标点后的新句子）进行：「我记得东方财富去年营收」不能被当成指令。
 */
public final class MemoryCommandParser {

    public static final int MAX_CONTENT_CHARS = 200;

    private static final Pattern SIX_DIGITS = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;\n]");

    /** 命令词：必须出现在句子开头才算。 */
    private static final List<String> COMMAND_PREFIXES =
            List.of("以后不要", "以后别再", "以后别", "别再", "记住", "请记住", "记得",
                    "以后", "今后", "从今以后", "重点关注", "关注");

    public Optional<MemoryCommand> parse(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        for (String clause : clauses(question)) {
            Optional<MemoryCommand> parsed = parseClause(clause);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private Optional<MemoryCommand> parseClause(String raw) {
        String clause = raw.strip();
        if (clause.isEmpty()) {
            return Optional.empty();
        }

        // 1) 负向指令优先：「以后不要…」「别再…」
        String negative = stripPrefix(clause, "以后不要", "以后别再", "以后别", "别再");
        if (negative != null) {
            // 命令词本身是内容的一部分（「不要再…」），补回去，否则召回时读起来像一句残句
            return command(LongTermMemory.TYPE_INSTRUCTION, "instruction.negative",
                    trimPunct("不要" + stripLeadingPunct(negative)), clause);
        }

        // 2) 关注对象（带 6 位代码）：只有命令词在句首才算，避免「我关注了 300059」
        if (startsWithCommand(clause) && clause.contains("关注")) {
            Matcher m = SIX_DIGITS.matcher(clause);
            if (m.find()) {
                String code = m.group(1);
                return command(LongTermMemory.TYPE_WATCHLIST, "focus.company." + code,
                        "长期关注 " + code, clause);
            }
        }

        // 3) 「记住：…」
        String remembered = stripPrefix(clause, "请记住", "记住", "记得");
        if (remembered != null) {
            String content = trimPunct(stripLeadingPunct(remembered));
            if (content.isBlank()) {
                return Optional.empty();
            }
            return command(LongTermMemory.TYPE_INSTRUCTION, keyOf(content), content, clause);
        }

        // 4) 「以后…」/「今后…」
        String after = stripPrefix(clause, "从今以后", "以后", "今后");
        if (after != null) {
            String content = trimPunct(stripLeadingPunct(after));
            content = stripDecorations(content);
            if (content.isBlank()) {
                return Optional.empty();
            }
            return command(LongTermMemory.TYPE_INSTRUCTION, keyOf(content), content, clause);
        }
        return Optional.empty();
    }

    /** 键不是让模型自由发挥的：同一个意思必须落到同一个 key，否则「只允许一条 ACTIVE」就失效了。 */
    static String keyOf(String content) {
        if (containsAny(content, "结论", "先给结论", "总结先行")) {
            return "answer.style";
        }
        if (containsAny(content, "中文", "英文", "语言", "用中", "用英")) {
            return "answer.language";
        }
        if (containsAny(content, "引用", "出处", "来源", "标注")) {
            return "analysis.must_cite";
        }
        if (containsAny(content, "表格", "列表", "分点")) {
            return "answer.format";
        }
        return "instruction.general";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithCommand(String clause) {
        for (String prefix : COMMAND_PREFIXES) {
            if (clause.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 命中则返回去掉前缀后的剩余部分（可能为空串），未命中返回 null。 */
    private static String stripPrefix(String clause, String... prefixes) {
        for (String prefix : prefixes) {
            if (clause.startsWith(prefix)) {
                return clause.substring(prefix.length());
            }
        }
        return null;
    }

    private static String stripLeadingPunct(String text) {
        int i = 0;
        while (i < text.length() && "：:，,、 　".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        return text.substring(i);
    }

    /** 「以后都回答先给结论」里的「都」「回答」是语气词，不是内容。 */
    private static String stripDecorations(String text) {
        String s = text.strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String deco : List.of("都", "一律", "每次", "每个", "请", "回答")) {
                if (s.startsWith(deco) && s.length() > deco.length()) {
                    s = s.substring(deco.length()).strip();
                    changed = true;
                }
            }
        }
        return s;
    }

    private static String trimPunct(String text) {
        String s = text.strip().replaceAll("\\s+", " ");
        while (!s.isEmpty() && "。！？!?.；;".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.length() > MAX_CONTENT_CHARS) {
            s = s.substring(0, MAX_CONTENT_CHARS) + "…";
        }
        return s;
    }

    private static List<String> clauses(String question) {
        List<String> parts = new ArrayList<>();
        for (String part : SENTENCE_END.split(question)) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static Optional<MemoryCommand> command(String type, String key, String content, String sourceRef) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String ref = sourceRef == null ? null
                : (sourceRef.length() > 200 ? sourceRef.substring(0, 200) : sourceRef);
        return Optional.of(new MemoryCommand(type, key, content, ref));
    }
}
