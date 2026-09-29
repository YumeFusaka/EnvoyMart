package yumefusaka.envoymart.promotionservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户持有的券。
 * <p>
 * 与模板分开：一张模板被成百上千人领取，各自的领取时间、有效期、
 * 使用状态都不同，塞进模板等于把用户维度压平。
 */
@Data
@TableName("user_coupon")
public class UserCouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long couponId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    /** 核销在哪张订单上。券用过就要能追溯到订单，否则对账时说不清优惠去了哪 */
    private String orderNo;
    private LocalDateTime receivedAt;
    private LocalDateTime usedAt;
    private LocalDateTime expireAt;
}
