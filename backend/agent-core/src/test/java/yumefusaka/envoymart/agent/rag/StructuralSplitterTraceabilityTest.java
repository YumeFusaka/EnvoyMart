package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分阶段的溯源信息。
 * <p>
 * 这些字段的特点是一致的：<b>丢了不会报错</b>。位置为空、偏移指错地方、
 * 版本没抄下来，检索照样返回结果、回答照样生成，只是引用从「可以核对」
 * 退化成「看起来像引用」。所以断言落在「能不能凭它找回原文」上，
 * 而不是落在「字段非空」上。
 */
class StructuralSplitterTraceabilityTest {

    private static final String CONTENT = """
            第一章 产品说明
            本品为膳食营养补充剂，不能替代药物治疗疾病。

            第二章 用法用量
            2.1 成人每日一次，每次一粒，随餐服用。
            2.2 孕妇及哺乳期女性请遵医嘱。

            第三章 注意事项
            3.1 对本品成分过敏者禁用。
            3.2 请置于儿童不能触及处。
            """;

    private final StructuralSplitter splitter = new StructuralSplitter(512, 40);

    private Document document() {
        return Document.builder()
                .id("vitd3_manual").title("维生素 D3 软胶囊说明书")
                .content(CONTENT)
                .source("manual").scope("nutrition").version("v2026.03")
                .build();
    }

    private List<DocumentChunk> chunks() {
        return splitter.split(document());
    }

    @Test
    void 每个切片都带上文档级的溯源信息() {
        assertThat(chunks()).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.getTitle()).isEqualTo("维生素 D3 软胶囊说明书");
            assertThat(chunk.getSource()).isEqualTo("manual");
            assertThat(chunk.getScope()).isEqualTo("nutrition");
            assertThat(chunk.getVersion()).isEqualTo("v2026.03");
            assertThat(chunk.getPosition()).isNotBlank();
        });
    }

    @Test
    void 位置串标出切片所属的章与条() {
        assertThat(chunks()).extracting(DocumentChunk::getPosition)
                .anySatisfy(position -> assertThat(position).contains("第二章"))
                .anySatisfy(position -> assertThat(position).contains("第三章"));
    }

    /**
     * 字符偏移的<b>唯一</b>用途是让前端能跳回原文并高亮，所以「非空」不算通过，
     * 必须真的能从原文的这个位置读回这段内容。偏移算错比没有偏移更糟：
     * 用户点进去看到的是另一段话，而他会以为自己看的是依据。
     */
    @Test
    void 字符偏移能在原文里定位到该切片的内容() {
        assertThat(chunks()).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.getCharOffset())
                    .as("切片 %s 没有字符偏移", chunk.getChunkId())
                    .isNotNull();

            int offset = chunk.getCharOffset();
            assertThat(offset).isBetween(0, CONTENT.length() - 1);

            // 块在原文里是从它的标题行开始的，所以偏移处读出来必须正好是这个标题。
            // 标题行在切片正文里被去掉了（位置前缀已经写了它），但偏移指的是块首，
            // 不是正文首——这个区别正是「跳回原文」能不能对上位置的关键。
            String heading = chunk.getPosition().substring(chunk.getPosition().lastIndexOf(" > ") + 3);
            assertThat(CONTENT.substring(offset))
                    .as("切片 %s 的偏移 %d 指向的不是它自己的位置（%s）", chunk.getChunkId(), offset, heading)
                    .startsWith(heading);
        });
    }

    @Test
    void 偏移随切片顺序单调递增() {
        List<DocumentChunk> chunks = chunks();

        assertThat(chunks).isNotEmpty();
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getCharOffset())
                    .as("第 %d 片的偏移不应早于第 %d 片", i, i - 1)
                    .isGreaterThan(chunks.get(i - 1).getCharOffset());
        }
    }

    /** 没有标题与版本的文档不该产出 "null" 字样的位置串 */
    @Test
    void 文档缺少元信息时不产出_null_字样() {
        List<DocumentChunk> chunks = splitter.split(Document.builder()
                .id("bare").title("无来源文档").content(CONTENT).build());

        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.getPosition()).doesNotContain("null");
            assertThat(chunk.getSource()).isNull();
            assertThat(chunk.getVersion()).isNull();
        });
    }
}
