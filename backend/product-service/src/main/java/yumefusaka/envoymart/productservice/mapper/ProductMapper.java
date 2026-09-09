package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.productservice.entity.ProductEntity;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {

    /**
     * 原子扣减库存 —— 判断与扣减在同一条语句里完成。
     * <p>
     * 不能用「查出来 → 判断够不够 → setStock 后 updateById」：那是读-改-写，
     * 两个并发请求会读到同一个旧值，各自算出新值再全字段覆盖，后写者抹掉前者的结果，
     * 库存被少扣。数据库端的条件更新天然串行，且 {@code stock >= #{quantity}}
     * 直接兜住了超卖。
     *
     * @return 影响行数，0 表示库存不足（或商品不存在），调用方必须据此判定失败
     */
    @Update("update product set stock = stock - #{quantity}, updated_at = current_timestamp "
            + "where id = #{id} and stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 原子回补库存。
     * <p>
     * 用 {@code stock = stock + #{quantity}} 而不是读出来加完再写回，
     * 避免与并发的扣减互相覆盖。
     */
    @Update("update product set stock = stock + #{quantity}, updated_at = current_timestamp "
            + "where id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);
}
