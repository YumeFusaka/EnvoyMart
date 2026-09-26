package yumefusaka.envoymart.agent.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档切片 —— 向量化和检索的基本粒度。
 */
@Data
@Builder
public class DocumentChunk {
    private String chunkId;
    private String docId;
    private String content;
    private int chunkIndex;

    /** 文档向量（Embedding 结果），维度由 `envoymart.embedding.dimension` 决定（默认 1024）。float 比 double 省一半空间。 */
    private float[] embedding;

    /**
     * BM25 索引文本；为空时退化为 {@link #content}。
     * <p>
     * 存在的理由：文档级检索要把标题与标签一并纳入关键词匹配（它们常含用户会说的词），
     * 但这些元信息不该出现在返回给模型的内容里。切片级检索由文档结构自带语境，
     * 留空即可。
     */
    private String indexText;
}
