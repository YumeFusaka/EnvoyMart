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
@Document(indexName = "envoymart_product", createIndex = false)
public class ProductIndex {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String subtitle;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String tags;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private Integer stock;

    @Field(type = FieldType.Integer)
    private Integer monthlySales;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String salesCopy;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String description;

    @Field(type = FieldType.Text, analyzer = "ik_smart")
    private String semanticKeywords;

    @Field(type = FieldType.Keyword)
    private String image;
}
