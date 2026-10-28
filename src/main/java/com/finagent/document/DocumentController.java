package com.finagent.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 阶段 7 文档上传 / 状态查询端点。
 *
 * <p>POST 上传后立即返回 documentId（parseStatus=PENDING），解析在 worker 里异步完成；
 * GET 轮询解析状态，直到 DONE（active_chunk_version 出现）。
 *
 * <p>装配条件与 {@link DocumentUploadService} 一致：COS 凭据缺失时本控制器不注册，
 * 应用照常启动（阶段 1 原则）。
 */
@RestController
@ConditionalOnProperty(prefix = "finagent.cos",
        name = {"secret-id", "secret-key", "region", "bucket"})
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentUploadService uploadService;
    private final DocumentRepository documents;

    public DocumentController(DocumentUploadService uploadService, DocumentRepository documents) {
        this.uploadService = uploadService;
        this.documents = documents;
    }

    /** 上传 PDF（multipart），异步解析，返回 documentId + 当前解析状态。 */
    @PostMapping("/api/documents")
    public ResponseEntity<Object> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam("companyCode") String companyCode,
                                         @RequestParam("companyName") String companyName,
                                         @RequestParam("market") String market,
                                         @RequestParam("docType") String docType,
                                         @RequestParam("docYear") Integer docYear,
                                         @RequestParam("title") String title) {
        byte[] pdf;
        try {
            pdf = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
        DocumentUploadService.UploadResult result =
                uploadService.upload(companyCode, companyName, market, docType, docYear, title, pdf);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", result.documentId());
        body.put("parseStatus", result.parseStatus());
        return ResponseEntity.ok(body);
    }

    /** 查询单个文档的解析状态与元数据（轮询用）。 */
    @GetMapping("/api/documents/{id}")
    public ResponseEntity<Object> detail(@PathVariable long id) {
        Optional<DocumentRepository.DocumentDetail> detail = documents.findDetail(id);
        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("NOT_FOUND", "文档不存在 id=" + id));
        }
        return ResponseEntity.ok(detail.get());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        log.debug("上传参数不合法：{}", e.getMessage());
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", e.getMessage()));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
