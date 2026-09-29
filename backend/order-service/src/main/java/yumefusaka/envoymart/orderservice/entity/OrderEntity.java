package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单。
 * <p>
 * 金额一律以「分」为单位的整数：浮点误差会破坏「商品总额 + 运费 - 优惠 = 应付」这个
 * 恒等式，而它必须成立 —— 那是能对用户解释清楚为什么收了这么多钱的唯一依据。
 */
@Data
@TableName("shop_order")
public class OrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String userId;
    /** 见 {@link yumefusaka.envoymart.orderservice.model.OrderStatus} */
    private String status;

    private Long totalAmount;
    private Long freightAmount;
    private Long discountAmount;
    private Long payAmount;

    /** 收货信息快照。地址簿后续被修改或删除都不影响历史订单 */
    private String receiverName;
    private String receiverPhone;
    private String receiverProvince;
    private String receiverCity;
    private String receiverDistrict;
    private String receiverDetail;

    /** 支付截止时间。到期未支付由定时任务关单并回补库存 */
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime closedAt;
    private LocalDateTime finishedAt;

    private String remark;
    private String cancelReason;
}
