package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 售后单。
 * <p>
 * 粒度是**订单行**：一次退货针对的是某一件商品，而不是整张订单。
 * 把售后挂在订单上，就只能表达「整单退」，而真实场景里用户常常只退其中一件。
 */
@Data
@TableName("after_sale")
public class AfterSaleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String afterSaleNo;
    private Long orderId;
    private String orderNo;
    private Long orderItemId;
    private String userId;
    /** REFUND_ONLY 仅退款 / RETURN_REFUND 退货退款 / EXCHANGE 换货 */
    private String type;
    /** APPLIED / APPROVED / RETURNING / RECEIVED / REFUNDING / FINISHED / REJECTED / CANCELLED */
    private String status;
    private String reason;
    private String description;
    private String images;
    /** 单位「分」 */
    private Long refundAmount;
    private LocalDateTime appliedAt;
    private LocalDateTime auditedAt;
    private LocalDateTime finishedAt;
    private String auditRemark;
}
