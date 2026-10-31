package com.finagent.document;

import com.finagent.chunk.ChunkRepository;
import com.finagent.scope.ScopeAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档列表与切块列表端点（阶段 10 文档页用）。
 *
 * <p>为什么不放进 {@link DocumentController}：那个控制器带
 * {@code @ConditionalOnProperty(finagent.cos.*)}（上传要 COS 凭据，缺凭据时整个控制器不注册）。
 * 「有哪些文档」「怎么切的块」与 COS 无关 —— 一起关掉，表现就是「没配 COS 时文档页 404」，
 * 而用户真正想知道的是「库里有什么、卡在哪个状态」。
 *
 * <p>与 {@code GET /api/scope/documents} 的区别（两者都不是给同一个用途）：
 * <ul>
 *   <li>{@code /api/scope/documents}：**能检索的**（DONE + 有 ACTIVE 块版本），
 *       给「指定文档」作用域的下拉框用；</li>
 *   <li>{@code /api/documents}：**全部**，含 PENDING / PARSING / FAILED，给文档管理页用。</li>
 * </ul>
 * 分开两个端点而不是加 {@code ?status=}：默认语义不同，合成一个必然有人传错参数。
 *
 * <p>两个端点都过 {@link ScopeAuthorizer}：租户内也可能有「不是你的」文档（PRIVATE / 未授权），
 * 枚举端点不过 ACL 就是设计 §15.4 的第 2 类越权（枚举泄漏）。
 */
@RestController
public class DocumentQueryController {

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ScopeAuthorizer authorizer;

    public DocumentQueryController(DocumentRepository documents, ChunkRepository chunks,
                                   ScopeAuthorizer authorizer) {
        this.documents = documents;
        this.chunks = chunks;
        this.authorizer = authorizer;
    }

    /** @param limit 默认 50，上限 200（在仓储里夹紧，避免把限流逻辑散在两层） */
    @GetMapping("/api/documents")
    public List<DocumentRepository.DocumentRow> list(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<DocumentRepository.DocumentRow> all = documents.listDocuments(limit);
        if (all.isEmpty()) {
            return all;
        }
        Set<Long> readable = new LinkedHashSet<>(
                authorizer.filterReadable(all.stream().map(DocumentRepository.DocumentRow::id).toList()));
        return all.stream().filter(row -> readable.contains(row.id())).toList();
    }

    /**
     * 切块列表：文档页「看切块」抽屉用。
     *
     * <p>不可读的文档返回空表而不是 404：抽屉已经打开过文档详情，
     * 这里再回 404 只会让前端处理两套错误；看不到就是空。
     */
    @GetMapping("/api/documents/{id}/chunks")
    public List<ChunkRepository.ChunkBrief> chunks(@PathVariable long id,
                                                   @RequestParam(value = "limit", defaultValue = "800") int limit,
                                                   @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (!authorizer.filterReadable(List.of(id)).contains(id)) {
            return List.of();
        }
        int version = chunks.activeVersion(id);
        if (version <= 0) {
            return List.of();
        }
        return chunks.listActiveChunks(id, version, limit, offset);
    }

    /**
     * 单块正文（正文角标的悬浮卡用，2026-10-30 ST18 需求 5）。
     *
     * <p>为什么单独一个端点而不是把正文塞进 citations：一次回答通常只用 2~5 条引用，
     * 但生成阶段要把整个证据包发给模型；引用里带全长正文会让每轮响应多几十 KB，
     * 而用户可能一次都不悬停。按需取 + 前端会话级缓存才是合适的粒度。
     *
     * <p>不可读一律 404（不是 403）：404 不泄漏「这个 chunkId 存在」。
     */
    @GetMapping("/api/chunks/{id}")
    public ResponseEntity<?> chunk(@PathVariable long id) {
        return chunks.findViewById(id)
                .filter(view -> authorizer.filterReadable(List.of(view.documentId())).contains(view.documentId()))
                .<ResponseEntity<?>>map(view -> ResponseEntity.ok(new ChunkDetail(
                        view.id(), view.documentId(), view.docTitle(), view.sectionPath(), view.title(),
                        view.chunkType(), view.pageStart(), view.pageEnd(), view.tokenCount(),
                        view.caption(), view.unit(), view.content())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "CHUNK_NOT_FOUND", "message", "切块不存在 id=" + id)));
    }

    /** 一块正文 + 定位信息（给前端悬浮卡渲染，不求全：只要用户能确认「这块在说什么」）。 */
    public record ChunkDetail(long id, long documentId, String docTitle, String sectionPath, String title,
                              String chunkType, Integer pageStart, Integer pageEnd, int tokenCount,
                              String caption, String unit, String content) {
    }
}
