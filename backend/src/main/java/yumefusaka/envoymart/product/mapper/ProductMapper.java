package yumefusaka.envoymart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.product.model.entity.ProductEntity;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
}
