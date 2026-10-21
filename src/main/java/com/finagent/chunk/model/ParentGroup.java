package com.finagent.chunk.model;

import com.finagent.document.DocumentModel;

import java.util.List;

/**
 * 一个 Parent 及其成员。
 *
 * <p>为什么带成员列表而不是只给拼好的字符串：Child 切分要按**段落边界**拆，
 * 只传拼接结果会丢失 block 边界，导致 Child 只能按字符硬切。
 */
public record ParentGroup(Chunk parent,
                          List<DocumentModel.Block> blocks,
                          List<DocumentModel.Table> tables) {
}
