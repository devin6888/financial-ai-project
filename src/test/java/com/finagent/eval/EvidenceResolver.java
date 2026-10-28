package com.finagent.eval;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把评测集里「按某个历史 chunk 版本 id 固定的证据块」解析成「当前 ACTIVE 版本里的等价块」。
 *
 * <p><b>为什么需要这一层</b>：阶段 7 之后重索引（reindex）变成常规操作 —— 每重切一次，
 * 新版本会拿到全新的 chunk id，旧版本保留但标 SUPERSEDED。评测集要是按 id 硬编码，
 * 每重切一次整份评测就失效（{@code GoldenSetTest} 会直接报「证据块不可用」）。
 *
 * <p><b>稳定的锚点是 {@code embedding_text_hash}</b>（契约 C1 里 embedding_text 拼法的 SHA-256）：
 * 内容没变 → hash 不变 → 跨版本可定位。所以解析链是
 * {@code 历史 id → hash → 当前 ACTIVE 块 id}。
 *
 * <p>注意：这里刻意不做「id 必须仍 ACTIVE」的校验 —— 那是评测集自检的事；
 * 本类只负责换算出当前等价块。
 */
final class EvidenceResolver {

    private EvidenceResolver() {
    }

    /** 历史证据 id → 当前 ACTIVE CHILD 块 id 集合（解析不出任何块的 id 会被忽略）。 */
    static Set<Long> resolveActiveIds(JdbcTemplate jdbc, List<Long> evidenceChunkIds) {
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : evidenceChunkIds) {
            String hash = hashOf(jdbc, id);
            if (hash == null || hash.isBlank()) {
                continue;
            }
            out.addAll(jdbc.queryForList(
                    "SELECT id FROM document_chunk"
                            + " WHERE embedding_text_hash = ? AND status = 'ACTIVE' AND chunk_level = 'CHILD'",
                    Long.class, hash));
        }
        return out;
    }

    /** 历史 id 对应的 embedding_text_hash；块不存在时返回 null。 */
    static String hashOf(JdbcTemplate jdbc, long chunkId) {
        List<String> hashes = jdbc.queryForList(
                "SELECT embedding_text_hash FROM document_chunk WHERE id = ?", String.class, chunkId);
        return hashes.isEmpty() ? null : hashes.get(0);
    }
}
