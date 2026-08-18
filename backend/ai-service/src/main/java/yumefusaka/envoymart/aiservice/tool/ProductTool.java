package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.ProductClient;

import java.util.Map;

/**
 * 商品工具 —— 商品搜索与推荐。
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
                .description("根据关键词搜索推荐商品。")
                .parameters(Map.of(
                        "query", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("搜索关键词").required(true).build(),
                        "limit", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("返回数量").required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String query = (String) call.getArguments().getOrDefault("query", "");
            int limit = call.getArguments().containsKey("limit")
                    ? Integer.parseInt(call.getArguments().get("limit").toString()) : 3;
            var products = productClient.recommend(query, limit).getData();
            StringBuilder sb = new StringBuilder("推荐商品：\n");
            products.forEach(p -> sb.append("- ").append(p.getName())
                    .append(" (").append(p.getPrice()).append(" 元)\n"));
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
}
