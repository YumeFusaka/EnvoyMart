package yumefusaka.envoymart.reviewservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「有用」的一票。
 * <p>
 * 唯一约束在 {@code (review_id, user_id)} 上：一人一条评价只能投一次。
 * 展示用的计数仍存在 {@code review.useful_count}——列表页不该为了一个数字
 * 去 join 一张会长大的表；这张表只回答「这个人投过没有」。
 */
@Data
@TableName("review_useful")
public class ReviewUsefulEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reviewId;
    private String userId;
    private LocalDateTime createdAt;
}
