package com.finagent.eval;

import com.finagent.scope.ChatScope;
import com.finagent.scope.ScopeKind;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 评测集加载器。
 *
 * <p>评测集是**测试资产**不是产品代码，所以放在 src/test 下；但它被 4 个测试共用，
 * 必须只有一份（复制一份就会各自漂移）。
 */
public final class GoldenSet {

    public static final Path DEFAULT_PATH = Path.of("src/test/resources/eval/golden-set.json");

    private GoldenSet() { }

    public static List<Item> load() {
        return load(DEFAULT_PATH);
    }

    public static List<Item> load(Path path) {
        try {
            Root root = JsonMapper.builder().build().readValue(Files.readString(path), Root.class);
            if (root == null || root.items() == null || root.items().isEmpty()) {
                throw new IllegalStateException("评测集为空：" + path);
            }
            return List.copyOf(root.items());
        } catch (IOException e) {
            throw new UncheckedIOException("读不到评测集：" + path, e);
        }
    }

    /**
     * @param evidenceChunkIds 由 SQL 反查得到（content LIKE %数值%），不是手写
     * @param expectRefusal    陷阱题：应当拒答
     */
    public record Item(String id, String type, String question, Scope scope,
                       List<Long> expectedDocIds, List<String> expectedValues,
                       List<Long> evidenceChunkIds, boolean expectRefusal, String note) {

        public static final String TYPE_METRIC = "METRIC";
        public static final String TYPE_NARRATIVE = "NARRATIVE";
        public static final String TYPE_COMPARISON = "COMPARISON";
        public static final String TYPE_TRAP = "TRAP";

        public Item {
            expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
            expectedValues = expectedValues == null ? List.of() : List.copyOf(expectedValues);
            evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
        }

        public boolean answerable() {
            return !expectRefusal;
        }

        public ChatScope toChatScope() {
            if (scope == null) {
                return ChatScope.corpus();
            }
            return new ChatScope(ScopeKind.valueOf(scope.kind()), scope.documentIds(), null);
        }

        public List<Long> documentIds() {
            return scope == null || scope.documentIds() == null ? List.of() : scope.documentIds();
        }
    }

    public record Scope(String kind, List<Long> documentIds) { }

    private record Root(String version, String builtBy, List<Item> items) { }
}
