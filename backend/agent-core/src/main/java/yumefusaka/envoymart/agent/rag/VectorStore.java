package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 向量存储 —— 支持语义相似度检索。
 * <p>
 * 检索入参是原始查询文本：向量化由实现自己负责，
 * 这样内存实现与远端实现（如 Milvus）才能共用同一套上层逻辑，
 * 也避免上层用 A 模型编码、存储用 B 模型编码导致的向量空间错配。
 */
public interface VectorStore {

    default void index(DocumentChunk chunk) {
        indexBatch(List.of(chunk));
    }

    void indexBatch(List<DocumentChunk> chunks);

    /** 传入原始查询文本，返回按相似度降序排列的 topK 切片。 */
    List<DocumentChunk> search(String query, int topK);

    /**
     * 带 {@code docId} 过滤的检索 —— 召回「只属于某一份文档」的切片。
     * <p>
     * <b>为什么过滤必须下沉到存储层。</b>情节记忆按 userId 隔离，原先的做法是先多取
     * （{@code limit * 20}）再由应用层筛掉别人的条目。<b>这是一个静默漏召回</b>：
     * 记忆库一大、某个用户的相关条目被挤出过取窗口，他就再也回忆不起自己说过的话，
     * 而且没有任何报错——日志里只会显示「这一轮召回 0 条」，
     * 与「这个用户确实没提过」长得一模一样。过滤下沉之后，「取 topK」的含义
     * 从「在所有切片里取前 topK，再筛掉大半」变成「在属于我的切片里取前 topK」。
     * <p>
     * <b>参数是 {@code docId} 而不是一段过滤表达式</b>：本仓库只有「按文档归属过滤」
     * 这一种需求，而表达式字符串会把「实现支不支持某语法」变成运行期才知道的事——
     * 内存实现要自己写解析器，Milvus 写错了要到线上才发现。窄接口的代价只是将来
     * 真需要更多维度时再加一个方法，而它可以被 <b>default 方法</b>兼容掉。
     * <p>
     * <b>默认实现是「筛完可能不足 topK」而不是「先取满再筛」</b>：后者会把
     * 不满足条件的名额补上不符合条件的切片，等于让调用方拿到一堆需要自己再筛一次的数据，
     * 过滤就没省下任何事。真实的实现要么把过滤下推到底层（Milvus），
     * 要么在内存里先按条件筛完再排序截断（见 {@code InMemoryVectorStore}）。
     * <p>
     * {@code docId} 为 null 或空白时退化为 {@link #search(String, int)}，
     * 让「不加过滤」只有一条语义。
     */
    default List<DocumentChunk> search(String query, int topK, String docId) {
        if (docId == null || docId.isBlank()) {
            return search(query, topK);
        }
        // 兜底实现：多取一些再筛。语义正确，但对「绝大多数条目都不属于我」的场景
        // 仍会漏召回 —— 真正的实现类应当覆盖它。这里给出 10 倍窗口是为了让
        // 尚未覆盖的第三方实现至少「比原来的 1 倍好」，而不是让它静默返回空
        return search(query, Math.max(topK * 10, 50)).stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .limit(topK)
                .toList();
    }

    void deleteByDocId(String docId);

    /**
     * 按切片 id 删除。
     * <p>
     * 粒度必须细到切片，因为配额淘汰是<b>按条</b>发生的：情节记忆挤到上限时挤出的是旧的那一条，
     * 不是这个用户的全部。用 {@link #deleteByDocId} 顶替会把整个用户删掉。
     * 若只从内存队列里移除、不删向量，被淘汰的条目会继续被召回——
     * 而上层已经查不到它了，还原出来只剩内容和默认类型，用户看到一条"自己没存过"的记忆。
     */
    void deleteByIds(List<String> chunkIds);

    /**
     * 清空整个集合。
     * <p>
     * 重建索引用的是它，不是逐篇 {@link #deleteByDocId}：后者只清得掉「这次的语料里有的」
     * 那些文档，清不掉语料改名、换目录、或早期种子数据留下的孤儿条目——
     * 它们没有溯源字段，被检索到时模型引用不了、用户点开无处可去，还挤占 topK 的名额。
     * 整份清空把「库里恰好等于当前语料」变成重建的<b>性质</b>，
     * 而不是「只要没人动过语料就成立」的假设。
     */
    void removeAll();
}
