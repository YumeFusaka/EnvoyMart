package yumefusaka.envoymart.reviewservice.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReviewRequest {
    @NotNull
    private Long productId;
    @NotNull
    private Long orderId;
    @NotNull
    private String userId;
    @NotNull @Min(1) @Max(5)
    private Integer rating;
    private String content;
    private String images;
}
