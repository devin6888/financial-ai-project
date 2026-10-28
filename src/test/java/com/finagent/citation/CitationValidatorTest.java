package com.finagent.citation;

import com.finagent.context.ContextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 引用校验：编号越界、去重、bbox 换算、scope 判定。 */
class CitationValidatorTest {

    private CitationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CitationValidator(JsonMapper.builder().build());
    }

    @Test
    @DisplayName("[1,3] → 两条引用，页码与 bbox 来自块本身")
    void mapsNumbersToCitations() {
        List<ContextBlock> blocks = List.of(
                block(1, 101, "TABLE", "元", null, 47),
                block(2, 102, "TEXT", null, null, 48),
                block(3, 103, "TEXT", null, null, 49));

        var r = validator.validate(List.of(1, 3), blocks);

        assertThat(r.citations()).extracting(Citation::childChunkId).containsExactly(101L, 103L);
        assertThat(r.citations()).extracting(Citation::pageStart).containsExactly(47, 49);
        assertThat(r.dropped()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("[1,1,2] → 去重成 2 条")
    void deduplicates() {
        var r = validator.validate(List.of(1, 1, 2), List.of(block(1, 101, "TEXT", null, null, 10),
                block(2, 102, "TEXT", null, null, 11)));

        assertThat(r.citations()).extracting(Citation::childChunkId).containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("[0,9] 越界 → 0 条引用 + 2 条 dropped + warning")
    void dropsOutOfRange() {
        var r = validator.validate(List.of(0, 9), List.of(block(1, 101, "TEXT", null, null, 10)));

        assertThat(r.citations()).isEmpty();
        assertThat(r.dropped()).containsExactly(0, 9);
        assertThat(r.warnings()).contains("citation_dropped:2", "citations_mostly_dropped");
    }

    @Test
    @DisplayName("空列表 / null → 空引用，不抛异常")
    void handlesEmpty() {
        assertThat(validator.validate(List.of(), List.of()).citations()).isEmpty();
        assertThat(validator.validate(null, null).citations()).isEmpty();
    }

    @Test
    @DisplayName("bbox {top,left,right,bottom} → {x,y,w,h}：x=left, y=top, w=right-left, h=bottom-top")
    void convertsBBox() {
        var r = validator.validate(List.of(1),
                List.of(block(1, 101, "TEXT", null, "{\"top\":0.4445,\"left\":0.1035,\"right\":0.9156,\"bottom\":0.5271}", 47)));

        BBox b = r.citations().get(0).bbox().get(0);
        assertThat(b.page()).isEqualTo(47);
        assertThat(b.x()).isEqualTo(0.1035);
        assertThat(b.y()).isEqualTo(0.4445);
        assertThat(b.w()).isCloseTo(0.8121, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.h()).isCloseTo(0.0826, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("bbox 为 null → bbox 为 null，其余字段照常")
    void nullBBoxIsSafe() {
        var c = validator.validate(List.of(1), List.of(block(1, 101, "TEXT", null, null, 10))).citations().get(0);

        assertThat(c.bbox()).isNull();
        assertThat(c.pageStart()).isEqualTo(10);
        assertThat(c.docTitle()).isEqualTo("东方财富2025年年度报告");
    }

    @Test
    @DisplayName("表格块 → evidenceType=TABLE，unit 透传；文本块 → TEXT")
    void evidenceTypeAndUnit() {
        var r = validator.validate(List.of(1, 2), List.of(
                block(1, 101, "TABLE", "元", null, 47),
                block(2, 102, "TEXT", "", null, 48)));

        assertThat(r.citations().get(0).evidenceType()).isEqualTo(Citation.TYPE_TABLE);
        assertThat(r.citations().get(0).unit()).isEqualTo("元");
        assertThat(r.citations().get(1).evidenceType()).isEqualTo(Citation.TYPE_TEXT);
        // 空串单位归一成 null，免得前端显示「单位：」
        assertThat(r.citations().get(1).unit()).isNull();
    }

    @Test
    @DisplayName("正文含「归属于母公司」→ scope=母公司；含合并报表 → 合并；都没有 → null")
    void detectsScope() {
        ContextBlock parent = withContent(block(1, 101, "TEXT", null, null, 47), "归属于母公司股东的净利润 9,610,120,743.64");
        ContextBlock consolidated = withContent(block(2, 102, "TEXT", null, null, 48), "合并资产负债表 单位：元");
        ContextBlock unknown = withContent(block(3, 103, "TEXT", null, null, 49), "公司主营业务为证券业务");

        var r = validator.validate(List.of(1, 2, 3), List.of(parent, consolidated, unknown));

        assertThat(r.citations()).extracting(Citation::scope)
                .containsExactly("母公司", "合并", null);
    }

    @Test
    @DisplayName("剔除率超过 50% → citations_mostly_dropped")
    void warnsWhenMostlyDropped() {
        var r = validator.validate(List.of(1, 2, 3, 4), List.of(block(1, 101, "TEXT", null, null, 10)));

        assertThat(r.citations()).hasSize(1);
        assertThat(r.dropped()).hasSize(3);
        assertThat(r.warnings()).contains("citations_mostly_dropped");
    }

    @Test
    @DisplayName("引用顺序与模型输出顺序一致，不按分数重排")
    void keepsEmittedOrder() {
        var r = validator.validate(List.of(3, 1), List.of(
                block(1, 101, "TEXT", null, null, 10),
                block(2, 102, "TEXT", null, null, 11),
                block(3, 103, "TEXT", null, null, 12)));

        assertThat(r.citations()).extracting(Citation::childChunkId).containsExactly(103L, 101L);
    }

    @Test
    @DisplayName("没有父的块（parentId=0）→ parentChunkId 为 null，不报一个假父 id")
    void orphanBlockHasNoParentRef() {
        ContextBlock orphan = new ContextBlock(1, 500L, 0L, 7L, "东方财富2025年年度报告", "第八节", "第八节",
                "正文", 1, 3, "TEXT", null, null, null, null, 0.9, 0, 1200, ContextBlock.ROLE_HIT);

        var c = validator.validate(List.of(1), List.of(orphan)).citations().get(0);

        assertThat(c.childChunkId()).isEqualTo(500L);
        assertThat(c.parentChunkId()).isNull();
    }

    // ------------------------------------------------------------ helpers

    private static ContextBlock block(int no, long id, String type, String unit, String bboxJson, int page) {
        return new ContextBlock(no, id, 500L, 7L, "东方财富2025年年度报告", "第二节 > 六、主要会计数据", "六、主要会计数据",
                "正文", page, page, type, null, unit, bboxJson == null ? null : bboxJson,
                bboxJson, 0.9, 10, 100, ContextBlock.ROLE_HIT);
    }

    private static ContextBlock withContent(ContextBlock b, String content) {
        return new ContextBlock(b.evidenceNo(), b.chunkId(), b.parentId(), b.documentId(), b.docTitle(),
                b.sectionPath(), b.title(), content, b.pageStart(), b.pageEnd(), b.chunkType(), b.caption(),
                b.unit(), b.headersJson(), b.bboxJson(), b.score(), b.chunkIndex(), b.tokenCount(), b.role());
    }
}
