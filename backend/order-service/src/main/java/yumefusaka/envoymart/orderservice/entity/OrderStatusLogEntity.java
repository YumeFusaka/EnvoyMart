package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态流水。
 * <p>
 * 用户问「我的订单为什么是这个状态」，答案在这里，不在客服的记忆里。
 * 建表时就在了，但一直没有任何代码写入 —— 那等于没有。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String fromStatus;
    private String toStatus;
    /** USER / SYSTEM / ADMIN */
    private String operatorType;
    private String operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
