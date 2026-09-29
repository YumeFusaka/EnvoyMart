package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeGraphBuilder;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;
import java.util.Map;

/**
 * 商品工具 —— 商品搜索与推荐。
 * <p>
 * <b>输出是给模型读的，不是给日志读的。</b>因此每一条都把商品编号带上：
 * 用户追问「第二个多少钱 / 有货吗」，模型下一步要么再搜一次（浪费一次往返），
 * 要么凭记忆编。
 * <p>
 * <b>编号印成 {@code SPU7} 而不是 {@code 7}</b>，因为那是这个商品在系统其余部分的名字：
 * 图谱上的节点键就是 {@code SPU7}（见 {@code KnowledgeGraphBuilder#key}），
 * {@code interaction_check} 的入参、图谱召回的依据里也都是它。印成裸数字的话，
 * 模型手上有同一个商品的两个名字，而它<b>没有依据判断这两个名字指的是同一个东西</b>——
 * 实测到的表现是：回答里刚说完「SPU7 的鱼油软胶囊」，转口就去搜「7」，
 * 搜不到之后告诉用户「编号 7 的商品不存在」。编号只要不一致，这个自相矛盾就会反复出现。
 * <p>
 * 反过来，这个工具也必须<b>认</b>这个编号：模型照着图谱里的写法传 {@code SPU7} 进来时，
 * 走一次精确查而不是拿它当关键词去搜——拿 {@code SPU7} 当关键词永远搜不到任何东西，
 * 而那会被模型读成「这个商品不存在」。
 */
@Slf4j
public class ProductTool implements Tool {

    /**
     * 商品编号。撇号位置与大小写都容忍，前导零吃掉——
     * {@code SPU7}、{@code spu007}、{@code SPU 7} 都是同一个商品。
     */
    private static final java.util.regex.Pattern SPU_KEY =
            java.util.regex.Pattern.compile("(?i)^spu\\s*0*(\\d+)$");

    private final ProductClient productClient;

    public ProductTool(ProductClient productClient) {
        this.productClient = productClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("product_search")
                .description("根据关键词搜索商品，返回商品的编号、名称、价格区间、库存与销量。"
                        + "用户想找商品、问价格、问有没有货时使用。"
                        + "已知商品编号（形如 SPU7）时把编号直接作为 query 传入，可精确查到那一个商品。")
                .parameters(Map.of(
                        "query", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("搜索关键词（例如「维生素D」「蛋白粉」），"
                                        + "或已知的商品编号（例如 SPU7）").required(true).build(),
                        "limit", ToolDefinition.ParameterSpec.builder()
                                .type("integer").description("返回数量，默认 3，最多 10。传编号查询时忽略").required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String query = String.valueOf(call.getArguments().getOrDefault("query", "")).strip();
            int limit = parseLimit(call.getArguments().get("limit"));

            java.util.regex.Matcher spu = SPU_KEY.matcher(query);
            boolean byKey = spu.matches();
            List<ProductSummary> products = byKey
                    ? lookupByKey(Long.parseLong(spu.group(1)))
                    : productClient.recommend(query, limit).getData();

            if (products == null || products.isEmpty()) {
                // 空结果也要给出一句明确的话：回空字符串时，模型没有任何可依据的事实，
                // 生成阶段就只能自己编一条「推荐」出来。
                // 两种查法的说法必须分开：按编号查空了，意思是「这个编号我认得、目录里没有它」；
                // 说成「没搜到相关内容」，模型会去怀疑编号格式不对，
                // 然后给用户编一段「平台编号可能是别的写法」——实测发生过
                return ToolResult.builder()
                        .success(true)
                        .output(byKey
                                ? "商品目录里没有编号 " + key(Long.parseLong(spu.group(1))) + " 的商品，可能已下架。"
                                : "没有找到与「" + query + "」相关的商品。")
                        .rawData(List.of())
                        // 只有关键词查空才算「没查到」：换个词、放宽或收紧条件都可能搜到，
                        // 执行图据此重规划一轮。按编号查空不算 —— 编号是精确的，
                        // 目录里没有就是没有，换什么说法都一样，标它只是白花一轮重规划
                        .noData(!byKey)
                        .build();
            }

            StringBuilder sb = new StringBuilder("找到 ").append(products.size()).append(" 个商品：\n");
            for (ProductSummary p : products) {
                sb.append("- 编号 ").append(p.getId() == null ? "未知" : key(p.getId()))
                        .append("：").append(p.getName());
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

    /**
     * 编号 → 商品的图谱节点键。
     * <p>
     * 委托给 {@code KnowledgeGraphBuilder#key} 而不是在这里拼一次 {@code "SPU" + id}：
     * 这两处算的是同一个<b>跨服务标识</b>，各写一遍就意味着「改了一边忘了另一边」，
     * 症状是模型照着这里的写法去查图谱，图谱认不出来（或反过来）。
     */
    private static String key(Long id) {
        return KnowledgeGraphBuilder.key(id);
    }

    /** 按编号精确查一个商品。查不到或服务报错都收敛成空列表，由调用方统一渲染成「没有」 */
    private List<ProductSummary> lookupByKey(Long id) {
        var response = productClient.getProduct(id);
        ProductSummary product = response == null ? null : response.getData();
        return product == null ? List.of() : List.of(product);
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
