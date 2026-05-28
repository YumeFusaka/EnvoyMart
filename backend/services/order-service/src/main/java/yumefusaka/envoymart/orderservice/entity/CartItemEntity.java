package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("cart_item")
public class CartItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long productId;
    private Integer quantity;
}
