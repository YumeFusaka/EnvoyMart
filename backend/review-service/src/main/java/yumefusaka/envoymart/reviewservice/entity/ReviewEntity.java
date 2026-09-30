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
    /**
     * 回复人。与 {@link #hiddenBy} 同理：回复代表商家在对外发言，
     * 改口之后要能回答「上一版是谁写的」。
     */
    private String replyBy;

    /**
     * 隐藏原因与操作人。
     * <p>
     * <b>隐藏是可以被滥用的动作</b>（商家隐藏差评），所以它不能是一条不留痕的状态位：
     * 没有这两列，被问起「这条评价为什么没了」时，系统里没有任何地方能回答。
     * 恢复时清空——{@code HIDDEN} 与「有隐藏原因」必须是同一件事的两个说法，
     * 留着一条已经恢复的评价的隐藏原因，会让「按原因排查」查出已经被撤销的操作。
     */
    private String hiddenReason;
    private String hiddenBy;
    private LocalDateTime hiddenAt;

    private Integer usefulCount;
    private LocalDateTime createdAt;
}
