package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.ProductCoverageRequest;
import yumefusaka.envoymart.contract.ProductGraphCoverage;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

/**
 * 商品资料覆盖率 —— 「在售商品里，多少是有说明书支撑的、多少没有」。
 * <p>
 * <b>为什么这个类在 ai-service</b>：算这个数要两份输入——「哪些商品在售」（product-service）
 * 与「图上有哪些商品节点、有没有文档边」（knowledge-service）。整个仓库里只有 ai-service
 * 同时够得着两者（它本来就在做实体链接，两份都有）。让管理台自己去拉商品目录再拼，
 * 等于把两份口径搬到浏览器里对齐，而这是本合同仓库反复踩过的分叉土壤。
 * <p>
 * <b>它把「没查成」与「全都覆盖了」分开</b>：商品目录拉不到、或图谱不可用时，
 * 返回的读数 {@code available=false}。这与「覆盖率 100%」在界面上长得一模一样，
 * 而它们是相反的两句话——一个是「干得挺好」，一个是「这次根本没查」。
 */
@Slf4j
@Service
public class ProductCoverageService {

    private final ProductClient productClient;
    private final KnowledgeClient knowledgeClient;

    public ProductCoverageService(ProductClient productClient, KnowledgeClient knowledgeClient) {
        this.productClient = productClient;
        this.knowledgeClient = knowledgeClient;
    }

    /**
     * 拉在售商品目录 → 问图谱覆盖 → 组装读数。
     * <p>
     * 商品目录直接用 {@code /products/internal/catalog}：它返回的就是**全部在售商品**，
     * 与实体链接用的是同一份来源。另开一个「覆盖率专用」的目录查询会让两份在售口径漂移——
     * 比如一边过滤了下架、另一边没过滤，于是覆盖率把下架商品也算进分母。
     */
    public ProductGraphCoverage coverage() {
        List<ProductSummary> catalog;
        try {
            Result<List<ProductSummary>> result = productClient.catalog();
            if (result == null || result.getCode() == null || result.getCode() != 200
                    || result.getData() == null) {
                log.warn("[Coverage] 商品目录拉取失败：{}", result == null ? "无响应" : result.getMsg());
                return unavailable("商品目录拉取失败");
            }
            catalog = result.getData();
        } catch (RuntimeException e) {
            log.warn("[Coverage] 商品目录拉取异常：{}", e.getMessage());
            return unavailable("商品目录拉取异常：" + e.getMessage());
        }

        if (catalog.isEmpty()) {
            // 空目录不是错误：一个刚上线的库本来就该没有商品。但它与「目录拉不到」
            // 必须分开——后者上面那条分支已经处理了
            return new ProductGraphCoverage(0, 0, List.of(), true, null);
        }

        ProductCoverageRequest request = new ProductCoverageRequest(catalog.stream()
                .map(p -> new ProductCoverageRequest.SpuRef(KnowledgeGraphBuilder.key(p.getId()), p.getName()))
                .toList());
        try {
            Result<ProductGraphCoverage> result = knowledgeClient.productCoverage(request);
            if (result == null || result.getCode() == null || result.getCode() != 200
                    || result.getData() == null) {
                log.warn("[Coverage] 图谱覆盖查询失败：{}", result == null ? "无响应" : result.getMsg());
                return unavailable("图谱覆盖查询失败");
            }
            ProductGraphCoverage data = result.getData();
            log.info("[Coverage] 在售 SPU {} 个，已覆盖 {} 个，未覆盖 {} 个（图谱可用={}）",
                    data.totalSpu(), data.coveredSpu(), data.uncoveredSpu().size(), data.available());
            return data;
        } catch (RuntimeException e) {
            log.warn("[Coverage] 图谱覆盖查询异常：{}", e.getMessage());
            return unavailable("图谱覆盖查询异常：" + e.getMessage());
        }
    }

    /**
     * 查不成的读数。
     * <p>
     * 三个计数一律给 0 并把 {@code available} 置 false：调用方**必须**先看 available。
     * 给一个「看起来像 100% 覆盖」的读数，比给一个明显的错误更糟——前者没人会去修。
     */
    private ProductGraphCoverage unavailable(String reason) {
        return new ProductGraphCoverage(0, 0, List.of(), false, reason);
    }
}