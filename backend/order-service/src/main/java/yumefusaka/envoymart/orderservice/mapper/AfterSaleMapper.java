package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.orderservice.entity.AfterSaleEntity;

@Mapper
public interface AfterSaleMapper extends BaseMapper<AfterSaleEntity> {
    @org.apache.ibatis.annotations.Select("select * from after_sale where id=#{id} for update")
    AfterSaleEntity lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
