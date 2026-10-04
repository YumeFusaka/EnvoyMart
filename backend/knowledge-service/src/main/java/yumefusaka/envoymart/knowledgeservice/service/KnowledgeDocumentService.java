package yumefusaka.envoymart.knowledgeservice.service;

import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
import yumefusaka.envoymart.knowledgeservice.model.ChunkDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentSummary;
import yumefusaka.envoymart.knowledgeservice.model.DocumentUpsertRequest;

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

    /**
     * 新建或更新一篇文档，并重切它的切片。
     * <p>
     * 与 {@link #seed()} 共用同一条 upsert 路径：两者的差别只在「文档从哪来」
     * （打包在 jar 里的种子 md / 管理端提交的正文），而「怎么落库、要不要重切」
     * 必须只有一份实现。分成两份的话，种子那边改了重切判据、上传这边不改，
     * 于是同一种情况在两个入口下行为不同——而它们写的是同一张表。
     *
     * @return 文档编号
     * @throws IllegalArgumentException 参数不合法（编号格式、范围取值、正文为空等）
     */
    String upsert(DocumentUpsertRequest request);

    /**
     * 停用 / 启用一篇文档。
     * <p>
     * 停用是「不再被检索到」而不是删除：引用是跨会话存在的历史事实，
     * 删掉文档会让已经发出去的 {@code [3]} 点开是 404。停用只让它不再进入
     * 检索索引（{@link #corpus()} 只下发启用状态的文档）。
     */
    void changeStatus(String docNo, int status);
}
