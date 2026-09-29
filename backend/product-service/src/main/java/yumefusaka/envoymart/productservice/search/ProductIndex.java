package yumefusaka.envoymart.productservice.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 商品搜索索引，粒度是 <b>SPU</b> 而不是 SKU。
 * <p>
 * 用户在列表页看到的是「维生素 D3 软胶囊」，不是一个具体的规格；把 SKU 铺进索引
 * 会让同一个商品在结果里出现三次（90 粒 / 180 粒 / 礼盒装），每一条还都只有部分信息。
 * 规格的差异用价格区间表达，选规格是详情页的事。
 * <p>
 * <b>索引名与旧模型不同</b>（{@code envoymart_spu}）：ES 的字段类型一旦建好就不能改，
 * 沿用旧名会在写入时报 "mapper [price] cannot be changed from type [double] to [long]"，
 * 而那个错误只在真正写入时才出现 —— 服务启动是成功的。
 * <p>
 * 中文分词用内置的 cjk（二元组），不用 ik_max_word：ik 要给 ES 装插件，镜像里没有，
 * 建索引时直接报 analyzer has not been configured。cjk 同样是二元组切分，
 * 与项目 BM25 那一路的中文策略一致，两路分词口径统一。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "envoymart_spu", createIndex = true)
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

    /** 商品参数拼成的文本（"剂型:胶囊 适用人群:成人"），让参数也参与检索 */
    @Field(type = FieldType.Text, analyzer = "cjk")
    private String attributeText;

    /** 排序用的时间戳（毫秒）。索引里没有它，「最新上架」就只能退回按销量排 */
    @Field(type = FieldType.Long)
    private Long createdAt;
}
