package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {
}
