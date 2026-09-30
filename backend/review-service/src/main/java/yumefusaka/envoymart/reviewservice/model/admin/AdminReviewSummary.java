package yumefusaka.envoymart.reviewservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端评价列表的一行。
 * <p>
 * 与 {@code ReviewResponse} 是两份东西，不是重复：那边是买家视角，而且
 * <b>匿名评价会把 {@code userId} 抹成 null</b>（脱敏在服务端做，不指望前端不显示）。
 * 运营要处理的恰恰是这类评价——「这条匿名的差评是谁写的、是不是同一个人在刷」——
 * 所以管理端必须拿到真实用户 id，这一点决定了它不能复用公开类型。
 * <p>
 * 隐藏的原因与操作人直接放在列表行上而不是挪到详情里：它是运营在列表上就要看的东西
 * （「这批差评是谁隐藏的」），而这三个字段本来就在 review 表上，多带出来不产生额外查询。
 */
@Data
@Builder
public class AdminReviewSummary {

    private Long id;
    private Long spuId;
    private Long skuId;
    /** 关联订单与订单行：处理纠纷时要能顺着摸到那笔交易 */
    private Long orderId;
    private Long orderItemId;
    /** 真实用户 id，匿名评价也照给 */
    private String userId;
    /** 该评价对外是否匿名展示。匿名只影响公开侧，不影响管理端能否看到是谁写的 */
    private Boolean anonymous;

    private Integer rating;
    private String content;
    private Integer imageCount;

    private String status;

    private String replyContent;
    private LocalDateTime replyAt;
    /** 回复人。回复是商家在对外发言，改口之后要能回答「上一版是谁写的」 */
    private String replyBy;

    /** 仅当状态为 HIDDEN 时非空——恢复时会被清空 */
    private String hiddenReason;
    private String hiddenBy;
    private LocalDateTime hiddenAt;

    private Integer usefulCount;

    private LocalDateTime createdAt;
}
