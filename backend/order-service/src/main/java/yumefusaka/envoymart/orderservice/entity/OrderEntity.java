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

    /** 买家留言，下单时填的。用户侧可见 */
    private String remark;

    /**
     * 商家备注，管理端写的内部说明（"客户要求周末送""这单已电话确认"）。
     * <p>
     * <b>与 {@link #remark} 是两个字段，不是同一个</b>：覆盖买家的留言会让那句
     * 「请放门口」永久消失，而它往往是售后争议里唯一能证明买家说过什么的东西。
     * 它是内部备注，因此不进 {@code contract.OrderResponse} ——
     * 那个类型同时被 AI 的订单工具消费，运营写给自己的话不该出现在给买家的回复里。
     */
    private String adminRemark;

    private String cancelReason;
}
