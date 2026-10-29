package com.finagent.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 测试数据的会话清理：只按主键删，不按标题模式（见 docs/测试规范.md 7.3）。
 *
 * <p>为什么不能按标题：标题会被被测代码改掉（首轮问题自动改名、改名用例故意改成 64 个字），
 * 一旦用 {@code WHERE title LIKE 'test-%'} 清理，改名后的行就永远留在库里。
 *
 * <p>删的顺序有讲究：{@code memory_event} 对会话没有外键，但它的 target_id 指向会话，
 * 先删事件再删会话；消息、摘要、结构化上下文都有 {@code ON DELETE CASCADE}，跟着会话走。
 */
public final class ConversationCleanup {

    private ConversationCleanup() {
    }

    public static void delete(JdbcTemplate jdbc, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> list = List.copyOf(ids);
        String in = placeholders(list.size());
        jdbc.update("DELETE FROM memory_event WHERE target_type IN ('CONVERSATION_CONTEXT','CONVERSATION_SUMMARY')"
                + " AND target_id IN (" + in + ")", list.toArray());
        jdbc.update("DELETE FROM conversation WHERE id IN (" + in + ")", list.toArray());
    }

    /** 兜底断言用：这些 id 在库里还剩几行，清理写错时能立刻发现。 */
    public static int remaining(JdbcTemplate jdbc, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> list = List.copyOf(ids);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE id IN (" + placeholders(list.size()) + ")",
                Integer.class, list.toArray());
        return count == null ? 0 : count;
    }

    static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}