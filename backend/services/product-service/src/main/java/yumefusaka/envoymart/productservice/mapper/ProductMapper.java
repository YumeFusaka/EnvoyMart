package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.productservice.entity.ProductEntity;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
}
