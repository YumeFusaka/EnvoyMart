package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.orderservice.entity.CartItemEntity;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {
}
