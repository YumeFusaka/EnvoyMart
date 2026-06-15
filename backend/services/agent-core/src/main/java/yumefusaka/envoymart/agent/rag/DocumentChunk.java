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

    /** 4096 维浮点向量（Embedding 结果）。float 比 double 省一半空间。 */
    private float[] embedding;
}
