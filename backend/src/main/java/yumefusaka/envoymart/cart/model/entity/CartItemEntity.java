package yumefusaka.envoymart.cart.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cart_item")
public class CartItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long productId;
    private Integer quantity;
    @TableField("selected_flag")
    private Boolean selected;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
