package com.finagent.document;

import com.finagent.storage.ObjectStorage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 从 COS 读回阶段 2 的产物。
 *
 * <p>这是 RECHUNK「不碰 PDF」的关键：产物已在对象存储，重切只需读它 ——
 * 181 页解析要两小时，重切只要几秒。
 *
 * <p>⚠️ {@code ObjectMapper} 必须是 <b>Jackson 3</b> 的（{@code tools.jackson}）——
 * 阶段 2 已踩过：用 {@code com.fasterxml.jackson} 会在启动时报 "No qualifying bean"，
 * 且编译期看不出来。但<b>注解</b>仍在 {@code com.fasterxml.jackson.annotation}（见 DocumentModel）。
 */
@Component
public class NormalizedDocumentLoader {

    private final ObjectStorage storage;
    private final ObjectMapper mapper;

    public NormalizedDocumentLoader(ObjectStorage storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    /** @param parsedObjectKey document.parsed_object_key */
    public DocumentModel load(String parsedObjectKey) {
        if (parsedObjectKey == null || parsedObjectKey.isBlank()) {
            throw new IllegalStateException("文档没有解析产物指针，无法重切");
        }
        byte[] raw = storage.get(parsedObjectKey);
        return mapper.readValue(raw, DocumentModel.class);
    }
}
