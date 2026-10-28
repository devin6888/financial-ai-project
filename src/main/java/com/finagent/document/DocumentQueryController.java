package com.finagent.document;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档列表端点（阶段 10 文档页用）。
 *
 * <p>为什么不放进 {@link DocumentController}：那个控制器带
 * {@code @ConditionalOnProperty(finagent.cos.*)}（上传要 COS 凭据，缺凭据时整个控制器不注册）。
 * 「有哪些文档」与 COS 无关 —— 把它一起关掉，表现就是「没配 COS 时文档页 404」，
 * 而用户真正想知道的是「库里有什么、卡在哪个状态」。
 *
 * <p>与 {@code GET /api/scope/documents} 的区别（两者都不是给同一个用途）：
 * <ul>
 *   <li>{@code /api/scope/documents}：**能检索的**（DONE + 有 ACTIVE 块版本），
 *       给「指定文档」作用域的下拉框用；</li>
 *   <li>{@code /api/documents}：**全部**，含 PENDING / PARSING / FAILED，给文档管理页用。</li>
 * </ul>
 * 分开两个端点而不是加 {@code ?status=}：默认语义不同，合成一个必然有人传错参数。
 */
@RestController
public class DocumentQueryController {

    private final DocumentRepository documents;

    public DocumentQueryController(DocumentRepository documents) {
        this.documents = documents;
    }

    /** @param limit 默认 50，上限 200（在仓储里夹紧，避免把限流逻辑散在两层） */
    @GetMapping("/api/documents")
    public List<DocumentRepository.DocumentRow> list(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return documents.listDocuments(limit);
    }
}
