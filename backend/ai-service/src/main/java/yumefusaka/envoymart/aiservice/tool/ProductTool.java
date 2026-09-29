package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;
import java.util.Map;

/**
 * 商品工具 —— 商品搜索与推荐。
 * <p>
 * <b>输出是给模型读的，不是给日志读的。</b>因此每一条都把商品 id 带上：
 * 用户追问「第二个多少钱 / 有货吗」，模型下一步要么再搜一次（浪费一次往返），
 * 要么凭记忆编。带上 id 之后它可以精确地调 {@code product_detail}。
 */
@Slf4j
public class ProductTool implements Tool {

    private final ProductClient productClient;

    public ProductTool(ProductClient productClient) {
        this.productClient = productClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("product_search")
                .description("根据关键词搜索商品，返回商品的编号、名称、价格区间、库存与销量。"
                        + "用户想找商品、问价格、问有没有货时使用。")
                .parameters(Map.of(
                        "query", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("搜索关键词，例如「维生素D」「蛋白粉」").required(true).build(),
                        "limit", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("返回数量，默认 3，最多 10").required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String query = String.valueOf(call.getArguments().getOrDefault("query", ""));
            int limit = parseLimit(call.getArguments().get("limit"));
            List<ProductSummary> products = productClient.recommend(query, limit).getData();

            if (products == null || products.isEmpty()) {
                // 空结果也要给出一句明确的话：回空字符串时，模型没有任何可依据的事实，
                // 生成阶段就只能自己编一条「推荐」出来
                return ToolResult.builder()
                        .success(true)
                        .output("没有找到与「" + query + "」相关的商品。")
                        .rawData(List.of())
                        .build();
            }

            StringBuilder sb = new StringBuilder("找到 ").append(products.size()).append(" 个商品：\n");
            for (ProductSummary p : products) {
                sb.append("- 编号 ").append(p.getId()).append("：").append(p.getName());
                if (p.getSubtitle() != null && !p.getSubtitle().isBlank()) {
                    sb.append("（").append(p.getSubtitle()).append("）");
                }
                sb.append("，价格 ").append(Money.yuanRange(p.getMinPrice(), p.getMaxPrice()));
                if (p.getTotalStock() != null) {
                    sb.append("，").append(p.getTotalStock() > 0 ? "有货" : "暂时无货");
                }
                if (p.getSales() != null && p.getSales() > 0) {
                    sb.append("，已售 ").append(p.getSales()).append(" 件");
                }
                if (p.getRatingAvg() != null && p.getRatingAvg().doubleValue() > 0) {
                    sb.append("，评分 ").append(p.getRatingAvg());
                }
                sb.append("\n");
            }

            return ToolResult.builder()
                    .success(true)
                    .output(sb.toString())
                    .rawData(products)
                    .build();
        } catch (Exception e) {
            log.error("[ProductTool] execute failed", e);
            return ToolResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    /** limit 由模型给出，可能是 "3"、3 或缺失；越界一律夹紧而不是报错 */
    private int parseLimit(Object raw) {
        if (raw == null) {
            return 3;
        }
        try {
            return Math.clamp(Integer.parseInt(String.valueOf(raw)), 1, 10);
        } catch (NumberFormatException e) {
            return 3;
        }
    }
}
