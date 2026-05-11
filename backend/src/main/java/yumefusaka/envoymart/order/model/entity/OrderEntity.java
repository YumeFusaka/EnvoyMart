package yumefusaka.envoymart.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("shop_order")
public class OrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String userId;
    private String recipientName;
    private String recipientPhone;
    private String address;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
