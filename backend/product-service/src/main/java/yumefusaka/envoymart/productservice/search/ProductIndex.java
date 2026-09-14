package yumefusaka.envoymart.productservice.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

/**
 * Elasticsearch 商品索引文档映射
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// createIndex = true：索引在首次写入时创建。
// 配合 ProductSyncService 的启动同步，搜索开箱可用；若两者都关着，/products/search 会一直报
// "Index envoymart_product not found" —— 功能静默不可用，而且不启动就发现不了。
//
// 中文分词用 ES 内置的 cjk（二元组），不用 ik_max_word：
// ik 需要给 ES 装插件，镜像里没有，建索引时直接报 analyzer [ik_max_word] has not been configured。
// 而 cjk 是内置的，且同样按二元组切分——和项目 BM25 那一路的中文策略一致，两路分词口径统一。
@Document(indexName = "envoymart_product", createIndex = true)
public class ProductIndex {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "cjk", searchAnalyzer = "cjk")
    private String name;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String subtitle;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String tags;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private Integer stock;

    @Field(type = FieldType.Integer)
    private Integer monthlySales;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String salesCopy;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String description;

    @Field(type = FieldType.Text, analyzer = "cjk")
    private String semanticKeywords;

    @Field(type = FieldType.Keyword)
    private String image;
}
