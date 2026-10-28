package com.finagent.document;

import com.finagent.scope.ScopeAuthorizer;
import com.finagent.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 引用 → 原文页图：GET /api/documents/{id}/pages/{page}?dpi=150
 *
 * <p>这个端点是「点 [E1] 看原文」的后端一半（前端在 EvidencePanel 的「原文」页签）。
 * 契约见 docs/关键契约.md C15。
 *
 * <p>三条安全规则，缺一条都会泄漏：
 * <ol>
 *   <li><b>先过 ACL 再读文件</b>：不可读的文档一律 404，不是 403 —— 403 等于确认「这份文档存在」；</li>
 *   <li><b>不接受对象键</b>：只接受 documentId，对象键永远不出服务端（否则等于给了任意文件读取）；</li>
 *   <li><b>只返回 png</b>：Content-Type 固定，不返回 PDF 原文，也不做重定向。</li>
 * </ol>
 */
@RestController
@ConditionalOnProperty(prefix = "finagent.cos", name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentPageController {

    private static final Logger log = LoggerFactory.getLogger(DocumentPageController.class);

    /** 72 太小、300 太慢；150 是 1080p 屏幕上看表格能看清的平衡点。 */
    private static final int DEFAULT_DPI = 150;
    private static final int MIN_DPI = 72;
    private static final int MAX_DPI = 300;

    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final PdfPageRenderer renderer;
    private final ScopeAuthorizer authorizer;

    public DocumentPageController(DocumentRepository documents, ObjectStorage storage,
                                  PdfPageRenderer renderer, ScopeAuthorizer authorizer) {
        this.documents = documents;
        this.storage = storage;
        this.renderer = renderer;
        this.authorizer = authorizer;
    }

    @GetMapping("/api/documents/{id}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable long id,
                                       @PathVariable int page,
                                       @RequestParam(name = "dpi", defaultValue = "" + DEFAULT_DPI) int dpi) {
        int safeDpi = Math.max(MIN_DPI, Math.min(MAX_DPI, dpi));

        if (!authorizer.filterReadable(List.of(id)).contains(id)) {
            throw new PageNotFound();
        }
        String objectKey;
        String contentHash;
        try {
            DocumentRepository.ParseInput input = documents.findParseInput(id);
            objectKey = input.objectKey();
            contentHash = input.contentHash();
        } catch (RuntimeException e) {
            throw new PageNotFound();
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new PageNotFound();
        }

        byte[] pdf;
        try {
            pdf = storage.get(objectKey);
        } catch (RuntimeException e) {
            log.warn("原文页图：读取 PDF 失败（document={}）：{}", id, e.getMessage());
            throw new PageNotFound();
        }

        PdfPageRenderer.PageImage image;
        try {
            image = renderer.render(id, contentHash == null ? "" : contentHash, pdf, page, safeDpi);
        } catch (PdfPageRenderer.PageNotFoundException e) {
            // 页码越界对外与「文档不存在」同一个 404：不确认文档有多少页
            throw new PageNotFound();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                // 宽高给前端算高亮框的百分比；不额外发一个 metadata 请求
                .header("X-Page-Width", String.valueOf(image.width()))
                .header("X-Page-Height", String.valueOf(image.height()))
                .body(image.png());
    }

    @ExceptionHandler(PageNotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(PageNotFound e) {
        return ResponseEntity.status(404).body(Map.of("error", "PAGE_NOT_FOUND", "message", "原文页不存在"));
    }

    /** 故意不区分「文档不存在 / 无权限 / 未解析 / 页码越界」：四种都 404。 */
    static class PageNotFound extends RuntimeException {
        PageNotFound() {
            super("page not found");
        }
    }
}
