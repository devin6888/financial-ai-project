package com.finagent.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 页图渲染的单测：不依赖真实年报，现场用 PDFBox 造一份两页 PDF。
 *
 * <p>断言的是「PNG 魔数 + 宽高 + 缓存命中」，不是像素内容 —— 像素比对在不同
 * 字体/渲染后端上会抖动，维护成本高于收益。
 */
class PdfPageRendererTest {

    private final PdfPageRenderer renderer = new PdfPageRenderer();

    @Test
    @DisplayName("渲染第 1 页：返回 PNG，且宽高与 150 dpi 的 A4 相符")
    void rendersFirstPage() {
        byte[] pdf = twoPagePdf();

        PdfPageRenderer.PageImage image = renderer.render(7L, "hash-1", pdf, 1, 150);

        assertThat(image.png()).hasSizeGreaterThan(1000);
        // PNG 魔数 89 50 4E 47
        assertThat(image.png()[0] & 0xFF).isEqualTo(0x89);
        assertThat(image.png()[1] & 0xFF).isEqualTo(0x50);
        assertThat(image.png()[2] & 0xFF).isEqualTo(0x4E);
        assertThat(image.png()[3] & 0xFF).isEqualTo(0x47);
        // PDPage 默认 LETTER = 612 x 792 pt，150 dpi 时 1275 x 1650 像素（允许 1 像素取整差）
        assertThat(image.width()).isBetween(1270, 1280);
        assertThat(image.height()).isBetween(1645, 1655);
    }

    @Test
    @DisplayName("同一文档同一页同一 dpi 命中缓存：第二次返回同一个字节数组")
    void secondRenderHitsCache() {
        byte[] pdf = twoPagePdf();

        PdfPageRenderer.PageImage first = renderer.render(7L, "hash-1", pdf, 1, 100);
        PdfPageRenderer.PageImage second = renderer.render(7L, "hash-1", pdf, 1, 100);

        assertThat(second.png()).isSameAs(first.png());
    }

    @Test
    @DisplayName("内容哈希变了就不复用缓存（重新上传过的文档）")
    void differentContentHashBypassesCache() {
        byte[] pdf = twoPagePdf();

        PdfPageRenderer.PageImage first = renderer.render(7L, "hash-1", pdf, 1, 100);
        PdfPageRenderer.PageImage second = renderer.render(7L, "hash-2", pdf, 1, 100);

        assertThat(second.png()).isNotSameAs(first.png());
        assertThat(second.png()).isEqualTo(first.png());
    }

    @Test
    @DisplayName("页码越界抛 PageNotFoundException（对外 404）")
    void outOfRangePage() {
        byte[] pdf = twoPagePdf();

        assertThatThrownBy(() -> renderer.render(7L, "hash-1", pdf, 3, 100))
                .isInstanceOf(PdfPageRenderer.PageNotFoundException.class)
                .hasMessageContaining("共 2 页");
        assertThatThrownBy(() -> renderer.render(7L, "hash-1", pdf, 0, 100))
                .isInstanceOf(PdfPageRenderer.PageNotFoundException.class);
    }

    @Test
    @DisplayName("不是 PDF 的字节抛 IllegalStateException（不把异常原文透给客户端）")
    void brokenPdf() {
        assertThatThrownBy(() -> renderer.render(7L, "hash-1", new byte[]{1, 2, 3}, 1, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PDF 渲染失败");
    }

    private static byte[] twoPagePdf() {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    stream.newLineAtOffset(72, 700);
                    stream.showText("page " + i + " revenue 16,067,741,039.51");
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
