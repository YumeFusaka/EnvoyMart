package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphIngestPayload;
import yumefusaka.envoymart.contract.GraphIngestResult;
import yumefusaka.envoymart.contract.InteractionReport;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
import yumefusaka.envoymart.contract.ProductCoverageRequest;
import yumefusaka.envoymart.contract.ProductGraphCoverage;
import yumefusaka.envoymart.contract.GraphBuildFailurePayload;

import java.util.List;

@FeignClient(name = "knowledge-service", url = "${services.knowledge-service-url:http://127.0.0.1:9008}")
public interface KnowledgeClient {

    /** 全量语料。知识库是事实源，这里是它唯一对外的语料出口 */
    @GetMapping("/knowledge/internal/corpus")
    Result<List<KnowledgeDocumentPayload>> corpus();

    /**
     * 交一篇文档的图谱贡献，替换它原有的全部边。
     * <p>
     * <b>抽取失败时不要调</b>：空列表与「没抽」在服务端看起来一模一样，
     * 而前者会把这篇文档已经建好的边全部清空。判据在调用方——只有拿到一份
     * 可信的抽取结果才发这个请求。
     */
    @PostMapping("/knowledge/internal/graph")
    Result<GraphIngestResult> ingestGraph(@RequestBody GraphIngestPayload payload);

    @PostMapping("/knowledge/internal/graph/failure")
    Result<Void> recordGraphFailure(@RequestBody GraphBuildFailurePayload payload);

    /** 整批重建后清理孤立实体 */
    @PostMapping("/knowledge/internal/graph/orphans")
    Result<Void> dropGraphOrphans();

    /**
     * 「这几样能不能一起用」——图谱侧的完整判定。
     * <p>
     * <b>走的是公开前缀而不是 {@code /internal}</b>，与上面三个方法不同。理由是这条查询
     * 没有「内部视角」：它返回的就是用户自己在界面上能看到的同一份东西（边上的原文引文
     * 来自知识库文档，文档本来就是登录后可读的）。再造一个 {@code /internal} 版本只会
     * 多出一条要登记进网关屏蔽清单的路径，而两条路径的实现必然开始漂移。
     * <p>
     * {@code items} 用逗号分隔，商品给 SPU 编号或完整商品名，其余给中文名。
     * <b>返回体里的 {@code available=false} 必须被当成「这一次没查成」</b>，
     * 不能念成「没有冲突」——空列表与「无风险」在界面上长得一模一样，
     * 而在这个场景里它们是相反的两句话。
     */
    @GetMapping("/knowledge/graph/interactions")
    Result<InteractionReport> interactions(@RequestParam("items") String items);

    /**
     * 一批商品的图谱覆盖读数 —— 覆盖率把关用。
     * <p>
     * 走 {@code /internal} 前缀：网关对这一段一律 404。它不返回用户数据，
     * 但它是「一次问几十个键」的内部批处理通道，不该对匿名请求开放。
     * <p>
     * 请求体里要带商品名：knowledge-service 不持有商品目录，
     * 回显的名字由调用方（ai-service，它刚拉过目录）提供。
     */
    @PostMapping("/knowledge/internal/graph/product-coverage")
    Result<ProductGraphCoverage> productCoverage(@RequestBody ProductCoverageRequest request);
    /**
     * 图谱召回 —— 混合检索的第三路。
     * <p>
     * 与 {@link #interactions} 的分工：那条路是用户点名要查的东西，逐项下结论；
     * 这条路只负责「从问题里认出实体、把图上相关的依据捞出来」，
     * 认不出就返回空——<b>空列表不等于「没有风险」</b>，它只是「这一路没捞到」，
     * 而降级的判断权在调用方（{@code GraphEvidenceRetriever}）。
     * <p>
     * 返回的每条边自带 docId / chunkId / 逐字引文，调用方据此拼出与知识库切片同构的依据。
     */
    @GetMapping("/knowledge/graph/recall")
    Result<List<GraphEdge>> recallGraph(@RequestParam("query") String query,
                                        @RequestParam("limit") int limit);
}
