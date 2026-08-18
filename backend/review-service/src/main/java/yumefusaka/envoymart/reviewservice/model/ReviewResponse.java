package yumefusaka.envoymart.reviewservice.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReviewResponse {
    private Long id;
    private Long productId;
    private Long orderId;
    private String userId;
    private Integer rating;
    private String content;
    private String images;
    private LocalDateTime createdAt;
}
