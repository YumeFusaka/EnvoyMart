package yumefusaka.envoymart.reviewservice.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review")
public class ReviewEntity {

    @TableId
    private Long id;
    private Long productId;
    private Long orderId;
    private String userId;
    private Integer rating;
    private String content;
    private String images;
    private LocalDateTime createdAt;
}
