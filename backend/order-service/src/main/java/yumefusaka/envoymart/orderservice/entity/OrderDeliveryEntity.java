package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 履约单（运单）。
 * <p>
 * 独立成实体而不是订单上的几个字段：承运商与运单号有独立生命周期，
 * 而轨迹是随时间增长的时间序列 —— 塞进订单表就只能存一条。
 */
@Data
@TableName("order_delivery")
public class OrderDeliveryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderNo;
    private String carrierCode;
    private String carrierName;
    private String trackingNo;
    private LocalDateTime shippedAt;
    private LocalDateTime signedAt;
}
