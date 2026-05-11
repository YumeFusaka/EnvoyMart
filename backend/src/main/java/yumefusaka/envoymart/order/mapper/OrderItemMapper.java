package yumefusaka.envoymart.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.order.model.entity.OrderItemEntity;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {
}
