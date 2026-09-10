package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 对「同一文档多切片」的处理 —— 回归防线。
 * <p>
 * 融合时若对每次出现都累加 {@code 1/(k+rank)}，一篇被切成 N 片的文档最高能拿到
 * 相当于单次出现 N 倍的分数。长文档因此被系统性抬权，足以压过真正更相关但只有一片的
 * 短文档——<b>文档长度不该是相关性的代理</b>。
 * <p>
 * 正确做法是：每篇文档在每个列表里只按它在该列表中的最佳排名计入一次。
 * 这条路径在小语料上永远暴露不出来（文档都短于切片长度，一篇恰好一片），
 * 所以必须用一个明确构造多切片场景的用例锁住。
 */
class RrfMultiChunkTest {

    private static final int TOP_K = 3;
    private static final String QUERY = "退货流程";

    /** 按固定顺序返回切片的桩向量库 */
    private static VectorStore stubStore(List<DocumentChunk> ranked) {
        return new VectorStore() {
            @Override
            public void indexBatch(List<DocumentChunk> chunks) {
                // 桩实现不索引
            }

            @Override
            public List<DocumentChunk> search(String query, int topK) {
                return ranked.stream().limit(topK).toList();
            }

            @Override
            public void deleteByDocId(String docId) {
                // 桩实现不删除
            }
        };
    }

    private static DocumentChunk chunk(String docId, int index, String content) {
        return DocumentChunk.builder()
                .chunkId(docId + "_" + index)
                .docId(docId)
                .chunkIndex(index)
                .content(content)
                .build();
    }

    @Test
    void 多切片文档不会仅因切片多而压过更相关的短文档() {
        // 向量路：短文档 short 排第一，长文档 long 的 5 个切片紧随其后
        List<DocumentChunk> vectorRanked = new ArrayList<>();
        vectorRanked.add(chunk("short", 0, QUERY + "：签收后 7 天内可发起，审核通过后寄回。"));
        for (int i = 0; i < 5; i++) {
            vectorRanked.add(chunk("long", i, "售后政策总览第 " + i + " 段，退货流程相关说明。"));
        }

        Document shortDoc = Document.builder()
                .id("short").title("退货流程")
                .content(QUERY + " " + QUERY + " 签收后 7 天内可发起，审核通过后寄回。")
                .tags(List.of("退货", "流程")).scope("after_sale").build();
        Document longDoc = Document.builder()
                .id("long").title("售后政策总览")
                .content("售后政策涵盖多个方面，此处仅零星提及退货。")
                .tags(List.of("售后")).scope("after_sale").build();

        Retriever retriever = new HybridRetriever(stubStore(vectorRanked), List.of(shortDoc, longDoc));

        List<DocumentChunk> results = retriever.retrieve(QUERY, TOP_K);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getDocId())
                .as("短文档在两路里都排第一，长文档只是切片多——切片数不该翻盘")
                .isEqualTo("short");
    }
}
