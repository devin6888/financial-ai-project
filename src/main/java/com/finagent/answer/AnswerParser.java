package com.finagent.answer;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 把模型输出解析成 {@link AnswerDto}。
 *
 * <p>为什么不用 {@code .call().entity(AnswerDto.class)}：那样拿不到原始文本，
 * 解析失败时既不能挽救（去围栏重试）也没法把原文写进日志。这里改为自己拿 content 再解析，
 * 顺带支持"围栏 + 前后废话"这两种最常见的不听话输出。
 */
@Component
public class AnswerParser {

    private final ObjectMapper objectMapper;

    public AnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnswerDto parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AnswerGenerationException("模型返回空内容");
        }
        String json = extractJson(raw);
        try {
            AnswerDto dto = objectMapper.readValue(json, AnswerDto.class);
            if (dto == null) {
                throw new AnswerGenerationException("模型输出解析为 null");
            }
            return dto;
        } catch (AnswerGenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AnswerGenerationException("模型输出不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /** 去掉 markdown 围栏，再截取第一个 { 到最后一个 } —— 允许模型在 JSON 前后写废话。 */
    static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            if (firstLine > 0) {
                s = s.substring(firstLine + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }
}
