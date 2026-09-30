package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条商品收藏记录（用户 × 商品 × 时间）。
 * <p>
 * {@code user_id} 是字符串而不是数字：网关注入的身份头就是字符串，
 * 与订单、售后、工单各库的保存方式一致。转成数字只会在某个 id 不是纯数字时炸在解析上，
 * 而收益是零——这一列从不参与算术。
 */
@Data
@TableName("user_favorite")
public class UserFavoriteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long spuId;
    private LocalDateTime createdAt;
}
