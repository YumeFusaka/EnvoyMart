package yumefusaka.envoymart.reviewservice.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发表评价。
 * <p>
 * 只传 {@code orderItemId} 与评分内容，<b>商品信息由服务端从订单行反查</b> ——
 * 采信请求体里的 spuId 等于让调用方决定自己在评哪个商品。
 */
@Data
public class CreateReviewRequest {

    @NotNull(message = "orderId 不能为空")
    private Long orderId;

    @NotNull(message = "orderItemId 不能为空")
    private Long orderItemId;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer rating;

    @Size(max = 1000, message = "评价内容最长 1000 字")
    private String content;

    /** 最多 9 张。数量上限在服务端也校验一次：前端限制拦不住直接调接口的请求 */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<String> images;

    private Boolean anonymous;
}
