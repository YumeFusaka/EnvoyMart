package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;

@Mapper
public interface ProductSpuMapper extends BaseMapper<ProductSpuEntity> {

    /**
     * 销量累加。
     * <p>
     * 用一条 SQL 原地自增，而不是「读出来、加上、写回去」：后者在两个消费者实例几乎
     * 同时处理同一商品的两笔订单时会互相覆盖，两笔成交量只加上去一笔，而且两个请求
     * 都返回成功。
     * <p>
     * 数量走 {@code #{quantity}} 占位而不是拼进 SQL：它来自消息体，属于外部输入。
     */
    @Update("update product_spu set sales = sales + #{quantity} where id = #{spuId}")
    int increaseSales(@Param("spuId") Long spuId, @Param("quantity") int quantity);
}
