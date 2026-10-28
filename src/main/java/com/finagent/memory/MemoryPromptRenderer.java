package com.finagent.memory;

import org.springframework.stereotype.Component;

/**
 * 把背景信息渲染成一段 Prompt 文本。
 *
 * <p><b>为什么要独立的渲染器而不是在装配器里拼字符串</b>：这段文本有两条硬约束
 * （顺序固定、必须带「不可引用」禁令），它不该散落在业务逻辑里；纯函数也便于单测
 * 每一条边界（空层省略、禁令句一定在末尾）。
 *
 * <p>禁令句固定、不参与裁剪：模型很擅长把「背景」当证据引用，一旦引用编号里出现
 * 记忆里的数字，{@code CitationValidator} 会剔除并降置信 —— 那是事后补救；
 * 这句话才是事前预防。
 */
@Component
public class MemoryPromptRenderer {

    static final String HEADER = "【背景信息 · 不可引用】";
    static final String FOOTER = "注意：以上仅为对话背景，不能作为答案依据；答案与数字必须来自下方【证据】，"
            + "不得引用本段任何内容，也不得使用其中的数字。";

    public String render(MemorySections sections) {
        if (sections == null || sections.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        if (sections.structured() != null && !sections.structured().isBlank()) {
            sb.append("会话上下文：").append(sections.structured().strip()).append('\n');
        }
        if (sections.summary() != null && !sections.summary().isBlank()) {
            sb.append("此前对话摘要：").append(sections.summary().strip()).append('\n');
        }
        if (!sections.shortTerm().isEmpty()) {
            sb.append("最近对话（从旧到新）：\n");
            for (ConversationMessage m : sections.shortTerm()) {
                sb.append(ConversationMessage.ROLE_USER.equals(m.role()) ? "用户：" : "助手：")
                        .append(m.content() == null ? "" : m.content().strip())
                        .append('\n');
            }
        }
        if (!sections.longTerm().isEmpty()) {
            sb.append("长期偏好与关注点：\n");
            for (String line : sections.longTerm()) {
                if (line != null && !line.isBlank()) {
                    sb.append("- ").append(line.strip()).append('\n');
                }
            }
        }
        sb.append(FOOTER);
        return sb.toString();
    }
}
