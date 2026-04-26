package yumefusaka.envoymart.paymentservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment")
public class PaymentEntity {

    @TableId
    private Long id;
    private Long orderId;
    private String orderNo;
    private String userId;
    private BigDecimal amount;
    private String status;
    private String transactionNo;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
