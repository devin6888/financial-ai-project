package com.finagent.chunk;

import com.finagent.document.DocumentModel;

import java.util.List;

/**
 * 几何工具：把多个片段的 bbox 并成「覆盖它们的矩形」。
 *
 * <p>为什么需要（评审 C6）：§8.8 要求 chunk 带 bbox 供前端高亮，而初版
 * ParentChunker 与 toChild 全传 null —— 验收 #4 因此不可能通过。
 * 真实产物里**每个 block 都有 bbox**，并集是现成可算的。
 */
public final class ChunkGeometry {

    /** 空列表返回 null（而不是一个假的零矩形）。 */
    public static String unionBbox(List<DocumentModel.BBox> boxes) {
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        boolean any = false;
        for (DocumentModel.BBox bb : boxes) {
            if (bb == null) {
                continue;
            }
            left = Math.min(left, bb.left());
            top = Math.min(top, bb.top());
            right = Math.max(right, bb.right());
            bottom = Math.max(bottom, bb.bottom());
            any = true;
        }
        if (!any) {
            return null;
        }
        return "{\"left\":%s,\"top\":%s,\"right\":%s,\"bottom\":%s}"
                .formatted(left, top, right, bottom);
    }

    private ChunkGeometry() {
    }
}
