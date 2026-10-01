package yumefusaka.envoymart.productservice.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * 商品搜索索引，粒度是 <b>SPU</b> 而不是 SKU。
 * <p>
 * 用户在列表页看到的是「维生素 D3 软胶囊」，不是一个具体的规格；把 SKU 铺进索引
 * 会让同一个商品在结果里出现三次（90 粒 / 180 粒 / 礼盒装），每一条还都只有部分信息。
 * 规格的差异用价格区间表达，选规格是详情页的事。
 * <p>
 * <b>索引名随字段结构变化升版</b>（{@code envoymart_product} → {@code envoymart_spu} →
 * 现在的 {@code envoymart_spu_v2}）：ES 的字段类型一旦建好就不能改，沿用旧名会在写入时报
 * "mapper [price] cannot be changed from type [double] to [long]"，而那个错误只在真正写入时
 * 才出现 —— 服务启动是成功的。加字段本来可以直接 {@code putMapping}，但那是一次**手工**操作：
 * 换了台机器、或者 ES 被清过，没人记得补，症状是筛选条件永远命中 0 条而全文检索一切正常。
 * 改名让 {@code createIndex} 重新按注解建库，是自解释的。
 * <p>
 * 中文分词用内置的 cjk（二元组），不用 ik_max_word：ik 要给 ES 装插件，镜像里没有，
 * 建索引时直接报 analyzer has not been configured。cjk 同样是二元组切分，
 * 与项目 BM25 那一路的中文策略一致，两路分词口径统一。
 * <p>
 * <b>v2 → v3：加了一条单字通道。</b>二元组有一个固有盲区，实测过：
 * {@code 碳酸钙 D3 咀嚼片} 切出来是 {@code [碳酸, 酸钙, d3, 咀嚼, 嚼片]}，
 * 而用户搜「钙片」切出来是 {@code [钙片]} —— 两边的词元没有一个是重合的，
 * 于是这个商品**搜不到**。加一个商品、让名字里出现「钙片」这两个相邻的字也没用：
 * 二元组只认边界固定的一刀，跨词界的复合词（钙片、孕妇、儿童DHA）全都落在这个盲区里，
 * 而项目里**商品检索只有 BM25 这一路、没有向量兜底**（知识检索那一路有 Milvus，
 * 所以同样的分词缺口在那里被语义通道盖住了）。
 * <p>
 * 修法不是换分词器，是让两条通道并存：二元组那条负责精确与打分（行为逐位不变），
 * {@link #unigramText} 这条逐字切、要求全字命中，负责把盲区兜回来。
 * 判定在 {@code ProductSearchService}，两处必须一起看。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "envoymart_spu_v3", createIndex = true)
public class ProductIndex {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "cjk", searchAnalyzer = "cjk")
    private String name;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String subtitle;

    /** 过滤用。查子树时先在服务层展开成 id 列表，再走 terms 查询 */
    @Field(type = FieldType.Long)
    private Long categoryId;

    /** 既参与搜索也用于展示，所以是 Text 而非 Keyword —— 代价是不能拿它做精确聚合 */
    @Field(type = FieldType.Text, analyzer = "cjk")
    private String categoryName;

    @Field(type = FieldType.Long)
    private Long brandId;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String brandName;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String tags;

    /** 价格单位「分」，与库里一致。区间是 SPU 维度的：取该 SPU 下所有 SKU 的最小/最大值 */
    @Field(type = FieldType.Long)
    private Long minPrice;

    @Field(type = FieldType.Long)
    private Long maxPrice;

    /** 该 SPU 下所有 SKU 的库存合计 */
    @Field(type = FieldType.Integer)
    private Integer totalStock;

    @Field(type = FieldType.Integer)
    private Integer sales;

    /** 评分与评价数落在索引里，列表页才能按评分排序、直接显示星星 */
    @Field(type = FieldType.Double)
    private Double ratingAvg;

    @Field(type = FieldType.Integer)
    private Integer reviewCount;

    @Field(type = FieldType.Integer)
    private Integer status;

    @Field(type = FieldType.Keyword)
    private String mainImage;

    /** 详情正文，转成纯文本后入索引：让「成分表里出现过的词」也能被搜到 */
    @Field(type = FieldType.Text, analyzer = "cjk")
    private String detailText;

    /**
     * 单字检索通道：上面那些字段拼成的一段文本，用 standard 分析器（对中日韩字符就是**逐字**切分）。
     * <p>
     * <b>与上面每个字段并存的理由，不是「多存一份」</b>：二元组认的是固定的一刀，
     * 「钙片」在 {@code 碳酸钙 D3 咀嚼片} 里根本不是一个词元；逐字切之后，两边的「钙」与「片」
     * 才对得上。查询侧要求<b>查询里的每个字都命中</b>（{@code operator=AND}），
     * 所以「钙片」不会退化成「含钙就行」或「含片就行」。
     * <p>
     * 拼成一段而不是逐字段各来一份：这一段只在「二元组一个都没命中」时才起作用，
     * 是兜底通道；兜底追求的是把该召回的召回来，不必再为它复制七份字段与七处维护。
     * 代价是跨字段的拼凑也算命中（名字里的「钙」配上详情里的「片」），
     * 兜底通道能接受这个精度损失，主通道仍是二元组。
     */
    @Field(type = FieldType.Text, analyzer = "standard")
    private String unigramText;

    /** 商品参数拼成的文本（"剂型:胶囊 适用人群:成人"），让参数也参与检索 */
    @Field(type = FieldType.Text, analyzer = "cjk")
    private String attributeText;

    /**
     * 商品参数的结构化形式，每项是 {@code 属性名:属性值}（"适用人群:老年人"）。
     * <p>
     * <b>与 {@link #attributeText} 并存不是冗余</b>：那一个是给全文检索用的，用户搜「胶囊」
     * 时能顺着参数命中文档；这一个给筛选用，要的是「属性值等于老年人」这个精确判断。
     * 多选属性的一个值一项——整串存 "适用人群:成人,老年人" 的话，筛「老年人」就漏了，
     * 而那种漏是静默的：结果少几条，看起来只像这个商品不满足条件。
     * <p>
     * {@code Keyword} 而不是 {@code Text}：筛选不能经分词器——分过之后 "软胶囊" 与 "胶囊"
     * 就成了同一堆 token 的不同组合，精确二字就没了。
     */
    @Field(type = FieldType.Keyword)
    private List<String> attributes;

    /** 排序用的时间戳（毫秒）。索引里没有它，「最新上架」就只能退回按销量排 */
    @Field(type = FieldType.Long)
    private Long createdAt;
}
