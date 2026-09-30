package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.productservice.entity.ProductSkuEntity;

import java.util.List;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSkuEntity> {

    /**
     * 原子扣减库存 —— 判断与扣减在同一条语句里完成。
     * <p>
     * 不能写成「查出来 → 判断够不够 → setStock 后 updateById」：那是读-改-写，
     * 两个并发请求会读到同一个旧值，各自算出新值再全字段覆盖，后写者抹掉前者的结果，
     * 库存被少扣。数据库端的条件更新天然串行，且 {@code stock >= #{quantity}}
     * 直接兜住了超卖。
     *
     * @return 影响行数，0 表示库存不足（或 SKU 不存在），调用方必须据此判定失败
     */
    @Update("update product_sku set stock = stock - #{quantity} "
            + "where id = #{skuId} and stock >= #{quantity} and status = 1")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 原子回补库存。用 {@code stock = stock + #{quantity}} 而不是读出来加完再写回，
     * 避免与并发的扣减互相覆盖。
     */
    @Update("update product_sku set stock = stock + #{quantity} where id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 按<b>绝对值</b>调整库存，且要求「这段时间里没人动过」。
     * <p>
     * 管理端录入的是盘点结果（目标库存），不是增量，所以不能写成 {@code stock = stock + delta}。
     * 但它同样不能「查出来 → setStock → updateById」——那是读-改-写：商家打开编辑页时看到 10，
     * 期间买家下单扣掉 1 件，点保存时把 10 原样写回去，那笔扣减就无声地消失了，
     * 库里比实际在库的多 1 件（等于超卖），流水也对不上账。
     * <p>
     * 做法是把读到的旧值放进 WHERE：0 行更新说明期间有人动过，调用方必须报冲突让人刷新重来，
     * 而不是当成写成功了。
     *
     * @param expect 提交前读到的库存值
     * @return 影响行数，0 表示库存已被并发修改（或 SKU 不存在），调用方必须据此判定失败
     */
    @Update("update product_sku set stock = #{target} where id = #{skuId} and stock = #{expect}")
    int updateStockIfUnchanged(@Param("skuId") Long skuId,
                               @Param("expect") int expect,
                               @Param("target") int target);

    /**
     * 记流水用的回读。
     * <p>
     * <b>必须在扣减/回补的同一个事务内调用</b>：上面那条 UPDATE 在 InnoDB 里会对该行
     * 加排他锁并持有到事务结束，所以这里读到的是自己刚写完的值，别人插不进来。
     * 一旦离开了事务边界再读，锁已释放，读到的可能是并发事务的结果，流水就不准了。
     */
    @Select("select stock from product_sku where id = #{skuId}")
    Integer selectStock(@Param("skuId") Long skuId);

    /** 批量取 SKU，用于订单详情组装。保持输入顺序由调用方负责 */
    @Select("<script>select * from product_sku where id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ProductSkuEntity> selectByIds(@Param("ids") List<Long> ids);
}
