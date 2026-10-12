package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("order_operation_receipt")
public class OperationReceiptEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String userId;
    private String operationId;
    private String toolName;
    private String parametersHash;
    private String globalXid;
    private String decisionMark;
    private Integer hashVersion;
    private Integer receiptVersion;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
