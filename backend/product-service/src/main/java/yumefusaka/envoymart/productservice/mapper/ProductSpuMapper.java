package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.productservice.entity.ProductSpuEntity;

import java.math.BigDecimal;

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

    /**
     * 带版本判据地写入评价聚合快照。
     * <p>
     * <b>判据必须落在 UPDATE 的 where 里，不能写在业务代码的 if 里。</b>
     * 「先 select 出来比一下版本、再 update」在两条消息并发时两个线程都会读到同一个旧版本、
     * 都判定自己更新，然后后一个把前一个覆盖掉——判据要生效，只能由数据库在一次原子操作里判。
     * <p>
     * 返回 0 有两种含义：语句没匹配到（商品不存在）或版本没更新（乱序的旧快照）。
     * 两者对调用方的处理是一样的：不刷新缓存、不记「已更新」。
     */
    @Update("update product_spu set rating_avg = #{ratingAvg}, review_count = #{reviewCount}, "
            + "aggregate_version = #{version} "
            + "where id = #{spuId} and aggregate_version < #{version}")
    int applyReviewAggregate(@Param("spuId") Long spuId,
                             @Param("ratingAvg") BigDecimal ratingAvg,
                             @Param("reviewCount") int reviewCount,
                             @Param("version") long version);
}
