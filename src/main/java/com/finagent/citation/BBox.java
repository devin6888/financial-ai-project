package com.finagent.citation;

/**
 * 引用高亮框。
 *
 * <p>坐标是<b>页内归一化</b>值（0~1），不是 PDF 点值：x/y 是左上角，w/h 是宽高。
 * 库里 {@code document_chunk.bbox} 存的是 {@code {top,left,right,bottom}}，
 * 换算见 {@link CitationValidator}。
 *
 * @param page 页码（1 起，取 chunk 的 page_start）
 */
public record BBox(int page, double x, double y, double w, double h) { }
