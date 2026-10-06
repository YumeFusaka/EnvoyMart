package yumefusaka.envoymart.aiservice.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import yumefusaka.envoymart.contract.ProductSummary;

/**
 * 商品检索工具的结构化产物 —— 一条检索结果在「给模型看的文字」之外的第二份表示。
 * <p>
 * <b>为什么需要这个类，而不是继续往 {@code rawData} 里塞 Map。</b>
 * 检索工具此前把结果转成 {@code List<Map<String,Object>>} 放进 rawData，
 * 用途只有一个：让后续步骤能写 {@code $0.skuId} 引用。这个用途没问题，
 * 但它把这个字段**独占**了 —— 下游「从工具结果里抽商品卡片」的代码只看
 * {@code ProductSummary} 实例，拿到 Map 一个都不认，于是
 * {@code recommendedProducts} 恒为 0。
 * <p>
 * 症状很隐蔽：<b>回答正文里商品名、价格、规格一样不少</b>（那是模型照着文字输出写的），
 * 只有卡片区空着。排查时看到的是「模型答得挺好，就是没卡片」，
 * 而根因在数据形状上，不在模型身上。这与 15 批那条「接口 200、日志干净、结果却是错的」
 * 是同一类问题：<b>信息在，但没有以约定好的形状交付</b>。
 * <p>
 * <b>所以这里给出的是「缺的那一份形状」</b>：SPU 摘要（卡片要显示名字、价格、销量）
 * + 默认规格编号（加购/下单要认 SKU）。两者都在同一份结果里，不额外查一次下游。
 * <p>
 * <b>保留 {@code skuId} 这个 getter 名字。</b>引用解析（{@code AgentGraph#readField}）
 * 走反射读 {@code getSkuId()}，而模型写的引用是 {@code $0.skuId} ——
 * 改名成 {@code defaultSku} 会让这条链路静默失效（取不到值时它只会在日志里留一行 warn，
 * 然后把占位串原样传给工具）。所以字段叫 defaultSku、对外的读法仍是 skuId：
 * 两个名字都是契约的一部分，各自有明确的读者。
 */
public record ProductSearchResult(ProductSummary summary, Long skuId) {

    /** 卡片可用的 SPU 摘要。工具结果的原始形态，直接透传 */
    public ProductSummary summary() {
        return summary;
    }

    /**
     * 把一批结果按检索顺序组装出来。
     *
     * @param products       检索到的 SPU 摘要，顺序即「第一个 / 第二个」的所指
     * @param skuBySpu       SPU 编号 → 默认规格编号。查不到时为 null（引用会原样保留占位串）
     */
    public static List<ProductSearchResult> of(List<ProductSummary> products, Map<Long, Long> skuBySpu) {
        return products.stream()
                .map(p -> new ProductSearchResult(p, skuBySpu.get(p.getId())))
                .toList();
    }

    /** SPU 编号 → 默认规格编号。空值也放进去：取不到与「没有这个键」在引用解析里表现一致（都是 null） */
    public static Map<Long, Long> skuIndex(List<ProductSummary> products,
                                           java.util.function.Function<Long, Long> resolver) {
        Map<Long, Long> index = new LinkedHashMap<>();
        for (ProductSummary product : products) {
            index.put(product.getId(), resolver.apply(product.getId()));
        }
        return index;
    }
}
