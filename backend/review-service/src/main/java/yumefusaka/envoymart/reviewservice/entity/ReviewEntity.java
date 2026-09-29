package yumefusaka.envoymart.reviewservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价。
 * <p>
 * 粒度是 <b>订单行</b>：评价针对的是「这笔订单里的这一件商品」，
 * 而不是笼统的「这个商品」。同一个人在不同订单里买同一件商品可以各评一次，
 * 而同一笔订单里的同一行只能评一次 —— 后者的唯一性由数据库约束保证
 * （应用层 selectCount 在并发下拦不住）。
 */
@Data
@TableName("review")
public class ReviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private Long skuId;
    private Long orderId;
    private Long orderItemId;
    private String userId;
    /** 1-5 星 */
    private Integer rating;
    private String content;
    private Integer isAnonymous;
    /** PENDING 待审核 / PUBLISHED 已发布 / HIDDEN 已隐藏 */
    private String status;
    private String replyContent;
    private LocalDateTime replyAt;
    private Integer usefulCount;
    private LocalDateTime createdAt;
}
