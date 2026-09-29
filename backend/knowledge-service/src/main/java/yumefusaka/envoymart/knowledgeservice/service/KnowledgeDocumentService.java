package yumefusaka.envoymart.knowledgeservice.service;

import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
import yumefusaka.envoymart.knowledgeservice.model.ChunkDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentSummary;

import java.util.List;

/**
 * 知识库读写。
 * <p>
 * 这一层是知识层的<b>事实源</b>：向量库与图谱都是它派生出去的东西，
 * 删掉可以重建，这里删了就真没了。
 */
public interface KnowledgeDocumentService {

    /**
     * 文档列表。
     *
     * @param scope 领域筛选，null 表示不限
     * @param status 0 停用 / 1 启用，null 表示不限
     */
    List<DocumentSummary> list(String scope, String keyword, Integer status);

    /** 文档详情，含全文与切片索引。找不到抛业务异常。 */
    DocumentDetail detail(String docNo);

    /**
     * 按切片 id 取切片与所属文档 —— <b>引用回跳的唯一入口</b>。
     * <p>
     * 检索命中带回来的就是 chunkId，用户点的也是它。找不到时抛异常而不是回 null：
     * 「这条引用指向的切片不存在」本身就是需要被看见的信号（切分参数漂移、
     * 文档被删、索引没重建），静默返回空会让前端显示一个没有内容的引用卡片。
     */
    ChunkDetail chunk(String chunkId);

    /**
     * 全量语料 —— 供 ai-service 构建检索索引。
     * <p>
     * 只返回启用状态的文档：停用是「不再被检索到」的意思，而索引用什么就检什么。
     */
    List<KnowledgeDocumentPayload> corpus();

    /**
     * 种子导入 —— 把 {@code resources/knowledge/*.md} 灌进库。
     * <p>
     * 按 {@code docNo} upsert，<b>正文或版本变了才重切</b>。不做「表空才导入」：
     * 那样改了 md 重启不生效，开发者以为自己改错了地方；也不做「每次启动全量重切」，
     * 那会让每次重启都产生一遍新的 chunkId，而引用是跨会话存在的。
     *
     * @return 本次真正新建或更新的文档编号
     */
    List<String> seed();
}
