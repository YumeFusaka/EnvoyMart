package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 检索器接口 —— 返回与查询相关的知识片段。
 */
public interface Retriever {

    List<DocumentChunk> retrieve(String query, int topK);
}
