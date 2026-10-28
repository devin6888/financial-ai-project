package com.finagent.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 PDF 的某一页渲染成 PNG，供前端「点引用看原文」。
 *
 * <p>为什么由后端渲染而不是前端拉 PDF 用 pdf.js：
 * <ol>
 *   <li>PDF 在 COS 私有桶里，前端要能拉就得发临时签名 URL —— 那等于把整份年报的访问权交出去，
 *       而引用只需要一页；</li>
 *   <li>pdf.js 是 350 KB 级别的额外依赖，只为看一页不值；</li>
 *   <li>渲染统一在服务端，客户端拿到的永远是 PNG，不会再解析 PDF（也就没有解析器差异）。</li>
 * </ol>
 *
 * <p>缓存：同一文档同一页同一 dpi 只渲一次。上限 {@value #MAX_CACHED_PAGES} 页，
 * 按最近使用淘汰 —— 演示时来回翻页不会反复烧 CPU，长时间运行也不会把内存吃满。
 * key 里带 contentHash，文档被重新上传后旧缓存自然失效。
 */
@Component
public class PdfPageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfPageRenderer.class);

    /** 约 300 KB/页，48 页 ≈ 15 MB 上限。 */
    static final int MAX_CACHED_PAGES = 48;

    private final Map<Key, PageImage> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, PageImage> eldest) {
                    return size() > MAX_CACHED_PAGES;
                }
            });

    /** 渲染结果：PNG 字节 + 像素尺寸（前端按百分比叠高亮框时要用宽高比）。 */
    public record PageImage(byte[] png, int width, int height) { }

    /** 缓存键：文档 id + 内容哈希 + 页码 + dpi。 */
    public record Key(long documentId, String contentHash, int page, int dpi) { }

    /**
     * @param page 1 起的页码
     * @param dpi  渲染分辨率，150 在 1080p 屏上清晰且单页约 300 KB
     * @throws PageNotFoundException 页码越界（调用方翻译成 404）
     * @throws IllegalStateException  PDF 损坏或渲染失败
     */
    public PageImage render(long documentId, String contentHash, byte[] pdf, int page, int dpi) {
        Key key = new Key(documentId, contentHash, page, dpi);
        PageImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        PageImage image = doRender(pdf, page, dpi);
        cache.put(key, image);
        return image;
    }

    private PageImage doRender(byte[] pdf, int page, int dpi) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (page < 1 || page > pages) {
                throw new PageNotFoundException("第 " + page + " 页不存在（共 " + pages + " 页）");
            }
            BufferedImage image = new PDFRenderer(document)
                    .renderImageWithDPI(page - 1, dpi, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            ImageIO.write(image, "png", out);
            return new PageImage(out.toByteArray(), image.getWidth(), image.getHeight());
        } catch (PageNotFoundException e) {
            throw e;
        } catch (IOException e) {
            log.warn("PDF 渲染失败（page={} dpi={}）：{}", page, dpi, e.getMessage());
            throw new IllegalStateException("PDF 渲染失败：" + e.getMessage(), e);
        }
    }

    /** 页码越界：对外是 404，不是 400 —— 不让调用方探测文档有多少页。 */
    public static class PageNotFoundException extends RuntimeException {
        public PageNotFoundException(String message) {
            super(message);
        }
    }
}
