package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {
    @Update("update cart_item set quantity=least(quantity+#{delta},99),updated_at=current_timestamp where user_id=#{userId} and sku_id=#{skuId}")
    int incrementQuantity(@Param("userId") String userId, @Param("skuId") Long skuId, @Param("delta") int delta);
    @Select("select * from cart_item where user_id=#{userId} and id=#{id} for update")
    CartItemEntity lockOwned(@Param("userId") String userId, @Param("id") Long id);
}
