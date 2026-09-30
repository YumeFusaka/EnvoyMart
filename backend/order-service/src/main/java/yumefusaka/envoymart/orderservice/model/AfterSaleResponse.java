package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 售后单。金额单位「分」 */
@Data
@Builder
public class AfterSaleResponse {

    private Long id;
    private String afterSaleNo;
    private Long orderId;
    private String orderNo;
    private Long orderItemId;
    /**
     * 申请人。
     * <p>
     * 用户侧看到的是他自己，无所谓；管理侧**必须有它** ——
     * 售后工作台上一行只写「退货退款 待审核」，处理的人不知道是谁提的，
     * 而这决定了要不要先打个电话问问。
     */
    private String userId;
    private String type;
    private String typeText;
    private String status;
    private String statusText;
    private String reason;
    private String description;
    private List<String> images;
    /** 申请金额（分） */
    private Long refundAmount;
    /** 申请时政策判定允许的上限（分），用于说明为什么只能退这么多 */
    private Long maxRefundable;

    private LocalDateTime appliedAt;
    private LocalDateTime auditedAt;
    private LocalDateTime finishedAt;
    private String auditRemark;

    /** 政策依据的文档编号 —— 售后的每个结论都能指回原文 */
    private String docRef;
    /** 商品快照，售后列表里要展示是哪件商品 */
    private String spuName;
    private String skuSpecText;
    private String skuImage;
}
