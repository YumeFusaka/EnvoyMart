package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeGraphBuilder;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
                .description("按关键词与条件搜索商品，返回商品的编号、名称、价格区间、库存与销量。"
                        + "用户想找商品、问价格、问有没有货时使用。"
                        + "**用户给出的约束要拆进各自的参数，不要拼进 query**："
                        + "query 只放商品本身的名字或类别词（如「维生素D」「蛋白粉」「钙片」），"
                        + "用户没提具体商品时留空；"
                        + "价格范围放 minPrice / maxPrice；"
                        + "用户明确说不要的东西放 excludeKeywords；"
                        + "「谁能吃 / 什么剂型 / 怎么储存」这类硬条件放 attributes。"
                        + "例：「孕妇能吃的钙片，200 以内」→ query=钙片, maxPrice=200, attributes=[\"适用人群:孕妇\"]；"
                        + "「乳糖不耐受能吃的」→ attributes=[\"是否含乳糖:不含\"]；"
                        + "「不要日版的」→ excludeKeywords=日本；"
                        + "「有没有适合老年人的」→ query 留空, attributes=[\"适用人群:老年人\"]。"
                        + "已知商品编号（形如 SPU7）时把编号直接作为 query 传入，可精确查到那一个商品。")
                .parameters(Map.of(
                        "query", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("搜索关键词（例如「维生素D」「蛋白粉」），"
                                        + "或已知的商品编号（例如 SPU7）。"
                                        + "用户只说条件、没提商品时留空，此时就是「按条件筛一遍」")
                                .required(false).build(),
                        "minPrice", ToolDefinition.ParameterSpec.builder()
                                .type("number").description("价格下限，**单位元**，只传数字（「100 元以上」传 100）")
                                .required(false).build(),
                        "maxPrice", ToolDefinition.ParameterSpec.builder()
                                .type("number").description("价格上限，**单位元**，只传数字（「300 以内」传 300）")
                                .required(false).build(),
                        "excludeKeywords", ToolDefinition.ParameterSpec.builder()
                                .type("string").description("用户明确**不要**的词，命中了它的商品会被排除。"
                                        + "它按**字面**排除，所以只用于用户说出口的、商品名里会出现的词"
                                        + "（「不要日版的」「不要含咖啡因的」）。"
                                        + "**饮食禁忌、能不能吃这类硬条件不要用它**：传「乳糖」会把写着"
                                        + "「不含乳糖」的商品一起排掉，而那恰好是唯一能吃的那个——这类条件走 attributes")
                                .required(false).build(),
                        "attributes", ToolDefinition.ParameterSpec.builder()
                                .type("array").items("string")
                                .description("按商品参数筛选，每项形如「属性名:属性值」，多项之间同时满足。"
                                        + "目前可用的：适用人群（成人 / 老年人 / 孕妇 / 儿童 / 乳糖不耐受人群）、"
                                        + "剂型（片剂 / 胶囊 / 软胶囊 / 软糖）、是否含乳糖（含 / 不含）、"
                                        + "储存条件（阴凉干燥处）。"
                                        + "「乳糖不耐受能吃的」「孕妇能吃的」「要片剂不要胶囊」都属于这一组。"
                                        + "**只在这一组取值里挑**：属性是精确匹配，写一个不存在的值不会报错、"
                                        + "只会把结果筛空，而那看起来像「平台上没有这类商品」")
                                .required(false).build(),
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
            // 两个分支各自在方法内部包 Downstream：它要的 Supplier 回的是 Result，
            // 而这两个方法回的是解好的业务对象，包不在这一层
            List<ProductSummary> products = byKey
                    ? lookupByKey(Long.parseLong(spu.group(1)))
                    : searchWithConstraints(call.getArguments(), query, limit);

            // 这里的 null 只可能是「确实没有这个商品」：下游 5xx 已经被 Downstream
            // 拦成异常了。原先两者都得到同一个 null，于是下游一抖，工具就说「没有找到」——
            // 用户以为商品下架了
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
                                : emptyResultMessage(call.getArguments(), query))
                        .rawData(List.of())
                        // 只有关键词查空才算「没查到」：换个词、放宽或收紧条件都可能搜到，
                        // 执行图据此重规划一轮。按编号查空不算 —— 编号是精确的，
                        // 目录里没有就是没有，换什么说法都一样，标它只是白花一轮重规划
                        .noData(!byKey)
                        .build();
            }

            StringBuilder sb = new StringBuilder();
            String condition = describeConstraints(call.getArguments());
            if (!condition.isEmpty()) {
                sb.append("（筛选条件：").append(condition).append("）\n");
            }
            sb.append("找到 ").append(products.size()).append(" 个商品：\n");
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
            return Downstream.failure("商品检索", e);
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

    /**
     * 按编号精确查一个商品。查不到收敛成空列表，由调用方渲染成「没有」。
     * <p>
     * 下游报错不再收敛成空列表：那与「已下架」是同一句话，而它说的是一件没发生过的事。
     * 现在那条路会抛出，由 {@link Downstream} 翻成「暂时不可用」。
     */
    private List<ProductSummary> lookupByKey(Long id) {
        ProductSummary product = Downstream.read("商品服务", () -> productClient.getProduct(id));
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

    /**
     * 带约束的检索。约束一律走各自的参数，<b>不拼进关键词</b>——
     * 「预算 300 以内不要乳糖的钙片」整句拼成一个字符串交给下游做子串匹配，
     * 是这条路此前一条结果都返回不了的根因。
     */
    private List<ProductSummary> searchWithConstraints(Map<String, Object> args, String query, int limit) {
        List<String> attributes = attributesOf(args);
        PageResult<ProductSummary> page = Downstream.read("商品服务", () -> productClient.search(
                query,
                toCents(args.get("minPrice")),
                toCents(args.get("maxPrice")),
                blankToNull(args.get("excludeKeywords")),
                attributes.isEmpty() ? null : attributes,
                limit));
        return page == null || page.getRecords() == null ? List.of() : page.getRecords();
    }

    /**
     * 属性条件。模型可能给数组、也可能在只有一个条件时给一个字符串，两种都收。
     * <p>
     * 值不做任何规范化：下游是精确匹配，我在这里「顺手」统一大小写或去空格的话，
     * 会把一个能筛到的值和筛不到的值都改成筛不到——用户和模型都看不出发生了什么。
     */
    private List<String> attributesOf(Map<String, Object> args) {
        Object raw = args.get("attributes");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::strip)
                    .filter(single -> !single.isEmpty()).toList();
        }
        if (raw == null) {
            return List.of();
        }
        String single = String.valueOf(raw).strip();
        return single.isEmpty() ? List.of() : List.of(single);
    }

    /**
     * 元 → 分。
     * <p>
     * 工具层收「元」是因为用户和模型说的都是元——让模型自己乘 100 是给它埋了一个
     * 必然出错的换算，而它错了之后，用户看到的是一个价格完全不对的结果集，
     * 界面上没有任何地方看得出这是单位问题。
     * <p>
     * 解析不出来时返回 null（当作没给这个条件）：<b>不限制</b>价格只是多给几条，
     * <b>限制错了</b>会给出用户明确不想要的。返回 null 的那一路会在输出里写明
     * 「价格条件未识别」，模型据此能纠正，而不是以为条件已经生效了。
     */
    private Long toCents(Object raw) {
        if (raw == null) {
            return null;
        }
        String digits = String.valueOf(raw).replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(digits) * 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * 把人给的条件复述成一句话，附在结果前面。
     * <p>
     * 有了它，模型向用户复述「我按 XX 筛的」时有依据，而不是自己重述一遍——
     * 重述就可能重述错，而用户没法判断哪个是真的。
     */
    private String describeConstraints(Map<String, Object> args) {
        List<String> parts = new java.util.ArrayList<>();
        Long min = toCents(args.get("minPrice"));
        Long max = toCents(args.get("maxPrice"));
        if (min != null && max != null) {
            parts.add("价格 " + Money.yuan(min) + "–" + Money.yuan(max));
        } else if (min != null) {
            parts.add("价格不低于 " + Money.yuan(min));
        } else if (max != null) {
            parts.add("价格不高于 " + Money.yuan(max));
        }
        if (args.get("minPrice") != null && min == null) {
            parts.add("价格下限未能识别，已忽略");
        }
        if (args.get("maxPrice") != null && max == null) {
            parts.add("价格上限未能识别，已忽略");
        }
        String exclude = blankToNull(args.get("excludeKeywords"));
        if (exclude != null) {
            parts.add("排除含「" + exclude + "」的");
        }
        List<String> attributes = attributesOf(args);
        if (!attributes.isEmpty()) {
            parts.add("属性 " + String.join("、", attributes));
        }
        return String.join("；", parts);
    }

    /**
     * 一条结果都没有时说什么。
     * <p>
     * 光说「没找到」，模型只能猜是哪个条件太严；猜错了就会给用户一个没用的建议
     * （「换个关键词试试」而实际是价格区间太窄）。所以这里**逐个撤掉一个条件再查一次**，
     * 找出把候选清空的那一个——最多三次下游调用（毫秒级），换的是模型能说出一句具体的话。
     * <p>
     * 只撤单个、不试组合：撤掉两个条件几乎总能查到东西，那样得出的「结论」
     * 对用户没有指导意义，还会让模型理直气壮地说错原因。
     */
    private String emptyResultMessage(Map<String, Object> args, String query) {
        String condition = describeConstraints(args);
        if (condition.isEmpty()) {
            return "没有找到与「" + query + "」相关的商品。";
        }
        String base = "没有找到同时满足这些条件的商品（" + condition + "）。";
        String blamed = firstConditionToBlame(args, query);
        return blamed == null
                ? base
                : base + "去掉" + blamed + "后能找到商品——可以把这一点告诉用户，让他决定。";
    }

    /**
     * 逐个撤掉一个条件重查，返回第一个「撤掉它就有结果」的条件说法。
     * <p>
     * 探针自己失败**不能把结论变成失败**——「没有结果」这件事已经查实了，
     * 不能因为一次额外的核实失败就把它降级成「下游不可用」。而且一次失败已经说明
     * 下游此刻不健康，后面的探针同样不可信，直接放弃而不是接着试。
     */
    private String firstConditionToBlame(Map<String, Object> args, String query) {
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        if (args.get("minPrice") != null || args.get("maxPrice") != null) {
            candidates.put("价格限制", without(args, "minPrice", "maxPrice"));
        }
        String exclude = blankToNull(args.get("excludeKeywords"));
        if (exclude != null) {
            candidates.put("排除条件「" + exclude + "」", without(args, "excludeKeywords"));
        }
        if (!attributesOf(args).isEmpty()) {
            candidates.put("属性筛选", without(args, "attributes"));
        }
        for (Map.Entry<String, Map<String, Object>> candidate : candidates.entrySet()) {
            try {
                if (!searchWithConstraints(candidate.getValue(), query, 1).isEmpty()) {
                    return candidate.getKey();
                }
            } catch (Exception e) {
                log.debug("[ProductTool] 空结果探针失败，放弃剩余探针: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** 复制参数并去掉指定键。不动原 map：它是模型这次调用的入参，下面还要拿它渲染输出 */
    private Map<String, Object> without(Map<String, Object> args, String... keys) {
        Map<String, Object> copy = new HashMap<>(args);
        for (String key : keys) {
            copy.remove(key);
        }
        return copy;
    }
}
