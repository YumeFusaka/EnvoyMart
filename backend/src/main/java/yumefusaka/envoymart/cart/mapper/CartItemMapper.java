package yumefusaka.envoymart.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.cart.model.entity.CartItemEntity;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {
}
