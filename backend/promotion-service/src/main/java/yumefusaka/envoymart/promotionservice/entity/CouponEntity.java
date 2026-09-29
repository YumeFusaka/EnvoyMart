package yumefusaka.envoymart.promotionservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板。
 * <p>
 * 金额一律「分」。{@code amount}（满减金额）与 {@code discount}（折扣率）互斥 ——
 * 用 type 区分，而不是让两个字段同时有值再靠调用方判断该用哪个。
 */
@Data
@TableName("coupon")
public class CouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** FIXED 满减 / DISCOUNT 折扣 */
    private String type;
    /** 满减金额（分）；折扣券为空 */
    private Long amount;
    /** 折扣率，如 0.85 表示八五折；满减券为空 */
    private BigDecimal discount;
    /** 使用门槛（分），0 表示无门槛 */
    private Long threshold;
    /** ALL 全场 / CATEGORY 限类目 / SPU 限商品 */
    private String scopeType;
    private String scopeIds;
    /** 发行量与已领取量 */
    private Integer totalCount;
    private Integer receivedCount;
    /** 领取后有效天数；为空则用下面的绝对时间 */
    private Integer validDays;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private LocalDateTime createdAt;
}
