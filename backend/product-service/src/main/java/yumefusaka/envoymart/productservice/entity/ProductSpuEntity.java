package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品概念层（"维生素 D3 软胶囊"）。价格与库存不在这里，在 {@link ProductSkuEntity} 上 */
@Data
@TableName("product_spu")
public class ProductSpuEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String spuCode;
    private String name;
    private String subtitle;
    private Long categoryId;
    private Long brandId;
    private String mainImage;
    /** 轮播图，逗号分隔。图片只用于展示、不参与查询条件，因此不做规范化 */
    private String images;
    private String detailHtml;
    /** 营销标签，逗号分隔。与「属性」的区别：属性描述事实，标签服务于运营 */
    private String tags;
    /** 0 下架 / 1 上架 */
    private Integer status;
    private Integer sales;
    /** 评分聚合冗余在这里：商品列表要按评分排序，聚合查询落不到索引上 */
    private BigDecimal ratingAvg;
    private Integer reviewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
