package yumefusaka.envoymart.reviewservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 评价图片。
 * <p>
 * 独立成表而不是在评价上存逗号分隔串：后者既无法限制数量，
 * 也无法单独下架某一张（比如审核不通过的那张）。
 */
@Data
@TableName("review_image")
public class ReviewImageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reviewId;
    private String url;
    private Integer sort;
}
