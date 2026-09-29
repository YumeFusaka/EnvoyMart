package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 售后单的状态流水。与订单的状态流水同理：用户问「为什么被拒了」，答案在这里 */
@Data
@TableName("after_sale_log")
public class AfterSaleLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long afterSaleId;
    private String fromStatus;
    private String toStatus;
    /** USER / SYSTEM / ADMIN */
    private String operatorType;
    private String operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
