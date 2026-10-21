package com.finagent.answer;

/** 模型输出无法解析成 AnswerDto。由 AnswerService 捕获并降级为 confidence=error。 */
public class AnswerGenerationException extends RuntimeException {
    public AnswerGenerationException(String message) {
        super(message);
    }

    public AnswerGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
