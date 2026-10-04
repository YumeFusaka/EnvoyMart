package yumefusaka.envoymart.productservice.model;

import lombok.Data;

import java.util.List;

/** 商品列表查询条件。价格区间单位是「分」，与库里保持一致 */
@Data
public class ProductQuery {

    private String keyword;

    /**
     * 属性筛选，每项形如 {@code 属性名:属性值}（"适用人群:老年人"），多项之间是 <b>AND</b>。
     * <p>
     * 与 {@link #keyword} 的分工：关键词回答「像不像我要找的」，走分词与相关性打分；
     * 属性回答「是不是满足这个硬条件」，走精确匹配、不参与打分。
     * 把「孕妇能吃」写进关键词里，它会变成一堆可以部分命中的词；
     * 写成属性它才是一条硬条件——而这两者给用户的结果完全不同。
     * <p>
     * 值必须是商品参数里**原样存在**的那一个（含大小写）。写错了不是报错而是筛空，
     * 所以工具层要把它显示给模型看（见 {@code product_search} 的输出前缀）。
     */
    private List<String> attributes;

    /**
     * 否定条件：文本里出现它的商品一律排除。
     * <p>
     * <b>这是粗筛，不是语义判断。</b>它匹配字面出现的词，所以排除「乳糖」会把写着
     * 「不含乳糖」的商品一起排除掉。**这类误伤是刻意的**：宁可少给几条，也不要给出用户
     * 明确说了不要的东西。代价是模型必须知道这件事——否则它会向用户宣称"已经筛掉了所有
     * 含乳糖的商品"，而事实是连不含的也一起没了。所以这条局限写进了工具描述。
     */
    private String excludeKeywords;

    /** 传一级或二级类目时，自动展开整棵子树 */
    private Long categoryId;
    private Long brandId;
    private Long minPrice;
    private Long maxPrice;
    /**
     * relevance（综合，默认）/ sales / price_asc / price_desc / newest / rating，
     * 见两条实现里的白名单。
     * <p>
     * <b>综合 = 相关性优先、同分看销量</b>，其余几项都是纯字段排序——ES 按字段排序时
     * <b>不计算得分</b>，相关性就整个没了。所以「不传」与「传 sales」不是同一件事：
     * 前者按相关度排，后者把相关度丢掉。实测丢掉之后搜「乳清蛋白粉」，
     * 排在第一的是维生素 C 咀嚼片（详情里提过「蛋白」），真正叫这名字的商品排第二。
     * <p>
     * 库内查询（MySQL）那条路没有相关性可言，{@code relevance} 在那里落到销量降序，
     * 与它缺省时的行为一致——同一组条件交给两条路，排序结果不会因为选了哪条而语义相反。
     */
    private String sort;

    private Integer page = 0;
    private Integer size = 20;

    /**
     * 对外页码，<b>从 0 开始</b>——数据库那条路与 ES 的 {@code PageRequest.of} 都按这个基准，
     * {@code PageResult.page} 与前端「第 N 页」也是。
     * <p>
     * <b>不要拿它直接构造 MyBatis-Plus 的 {@code Page}</b>：那边 {@code current} 从 1 开始，
     * 且 {@code offset()} 对 {@code current <= 1} 一律返回 0，于是第 0 页与第 1 页查出
     * 同一批数据、之后整体后移一页，最后一页永远取不到。要传给 MP 用 {@link #mpCurrent()}。
     */
    public int zeroBasedPage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** MyBatis-Plus 的页码从 1 开始。这步转换只留这一个出处，免得各调用点各自 +1 */
    public long mpCurrent() {
        return zeroBasedPage() + 1L;
    }

    /** 上限 100：不设上限的话，一个 `size=100000` 的请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    /**
     * 这一组条件<b>只有 ES 那条路实现得了</b>（属性是结构化匹配、否定条件要在拼好的检索文本上做差，
     * 两者都依赖索引里的字段，MySQL 的库内查询碰不到）。
     * <p>
     * 存在的理由是入口处分流：同一个 {@code ProductQuery} 会被两条路执行——
     * {@code /products}（MySQL）与 {@code /products/search}（ES）。
     * 不认这两个条件的那条路如果照单全收地跑，<b>筛掉的东西会原样返回</b>：
     * 不报错、不打日志，调用方拿到的是「筛选没生效」的全量结果，
     * 而把同一个 query 原样发到另一个入口又是对的。判定放在这一个方法里，
     * 好过在每个入口各写一遍 `attributes != null || ...`——那种写法早晚会漏掉一个入口。
     */
    public boolean needsFullTextIndex() {
        return (attributes != null && !attributes.isEmpty())
                || (excludeKeywords != null && !excludeKeywords.isBlank());
    }
}
