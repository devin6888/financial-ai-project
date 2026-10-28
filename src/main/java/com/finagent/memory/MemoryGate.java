package com.finagent.memory;

import com.finagent.understanding.QueryUnderstanding;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆的召回闸门（设计 §11.11，不引入独立分类器）。
 *
 * <p>为什么要有闸门：「每次问答都召回一次记忆」等于每次问答多一次向量化调用 + 一次向量检索，
 * 而 90% 的财务事实问答根本用不到用户偏好。闸门不通过就<b>连库都不查</b>。
 *
 * <p>两条来源任一命中即召回：规则词典（快、可解释）与 QU 的 needLongTermMemory（模型语义判断）。
 * 默认 false —— 宁可漏召回一次，也不要白花钱。
 */
@Component
public class MemoryGate {

    /** 出现这些词就认为「这一问可能在说偏好/习惯」，与记忆内容无关。 */
    static final List<String> KEYWORDS = List.of(
            "以后", "以前", "之前说", "上次", "照旧", "还是按", "格式", "偏好",
            "习惯", "记住", "一贯", "历来", "按我的", "照我");

    private final MemoryProperties props;

    public MemoryGate(MemoryProperties props) {
        this.props = props;
    }

    public boolean shouldRecall(String question, QueryUnderstanding qu) {
        if (!props.gateEnabled() || !props.enabled()) {
            return false;
        }
        if (question == null || question.isBlank()) {
            return false;
        }
        if (qu != null && qu.needLongTermMemory()) {
            return true;
        }
        for (String keyword : KEYWORDS) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
