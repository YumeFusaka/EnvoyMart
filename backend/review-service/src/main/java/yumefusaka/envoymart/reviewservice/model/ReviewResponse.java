package yumefusaka.envoymart.reviewservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 评价。金额无关，不涉及「分」 */
@Data
@Builder
public class ReviewResponse {

    private Long id;
    private Long spuId;
    private Long skuId;
    private Long orderId;
    private String userId;
    /** 匿名评价不返回 userId，只返回 null —— 脱敏在服务端做，不指望前端不显示 */
    private String nickname;
    private Integer rating;
    private String content;
    private List<String> images;
    private Boolean anonymous;
    private String status;
    private String replyContent;
    private LocalDateTime replyAt;
    private Integer usefulCount;
    private LocalDateTime createdAt;
}
