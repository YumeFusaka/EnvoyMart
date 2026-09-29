package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.productservice.entity.ProductSkuSpecEntity;
import yumefusaka.envoymart.productservice.model.SkuSpecView;

import java.util.List;

@Mapper
public interface ProductSkuSpecMapper extends BaseMapper<ProductSkuSpecEntity> {

    /**
     * 一次查出一批 SKU 的规格明细。
     * <p>
     * 用 join 而不是「先查关联、再逐条查规格名」：详情页要展示的是一张规格表，
     * 逐条查会变成 N+1 次往返，而 SKU 数量并不多，一次 join 的代价小得多。
     */
    @Select("<script>"
            + "select s.sku_id, s.spec_id, p.name as spec_name, p.sort as spec_sort, "
            + "       s.spec_value_id, v.spec_value "
            + "from product_sku_spec s "
            + "join product_spec p on p.id = s.spec_id "
            + "join product_spec_value v on v.id = s.spec_value_id "
            + "where s.sku_id in "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "order by p.sort, v.sort"
            + "</script>")
    List<SkuSpecView> selectBySkuIds(@Param("skuIds") List<Long> skuIds);
}
