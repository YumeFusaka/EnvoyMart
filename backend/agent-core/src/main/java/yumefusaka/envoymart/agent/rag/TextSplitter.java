package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 文档切分策略 —— 把一篇文档切成可向量化的切片。
 * <p>
 * 抽成接口是为了让切分策略可以被替换与对比：
 * {@link FixedSizeSplitter} 是定长滑动窗口（原实现），
 * {@link StructuralSplitter} 是按文档结构分层下钻。
 * 两者的质量差异由 {@code ChunkingQualityTest} 用长文档语料度量。
 */
public interface TextSplitter {

    List<DocumentChunk> split(Document doc);
}
