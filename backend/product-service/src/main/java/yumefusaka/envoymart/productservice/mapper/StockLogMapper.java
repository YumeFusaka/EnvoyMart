package yumefusaka.envoymart.productservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.productservice.entity.StockLogEntity;

import java.util.List;

@Mapper
public interface StockLogMapper extends BaseMapper<StockLogEntity> {

    /**
     * 这批 SKU 里有多少条「因订单而变动」的流水 —— 也就是它们有没有真的被卖出过。
     * <p>
     * 管理端删除 SKU 前靠它做判断。<b>不能拿商品上的 sales 冗余字段代替</b>：
     * 那个数字是展示用的聚合，会被各种口径改动；而库存流水是「这笔订单真的扣过这个 SKU」
     * 留下的原始凭证，订单详情里引用的正是 SKU id。删掉一个被订单引用过的 SKU，
     * 那些历史订单会变成查不到商品的空壳——而且是在用户翻旧订单时才暴露。
     *
     * @return 命中「ORDER 类型流水」的 SKU 个数；返回 0 说明这批 SKU 从未被卖出
     */
    @Select("<script>select count(distinct sku_id) from stock_log "
            + "where biz_type = 'ORDER' and sku_id in "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int countOrderedSkus(@Param("skuIds") List<Long> skuIds);

    /**
     * 回填一条已认领流水的变动前后值。
     *
     * <p>回补的幂等认领必须先于库存更新写入（否则并发下认领不到），
     * 而那一刻 {@code afterStock} 还不知道，所以先用占位行认领、再回来更新它。
     * 两步同事务，中途失败一起回滚，不会留下数字不对的流水。
     *
     * @return 影响行数；0 说明认领时写的行不见了，属异常，调用方应据此报警
     */
    @Update("update stock_log set before_stock = #{beforeStock}, after_stock = #{afterStock} "
            + "where biz_type = #{bizType} and biz_id = #{bizId} "
            + "and sku_id = #{skuId} and change_type = #{changeType}")
    int updateAfterStock(@Param("bizType") String bizType, @Param("bizId") String bizId,
                         @Param("skuId") Long skuId, @Param("changeType") String changeType,
                         @Param("beforeStock") int beforeStock, @Param("afterStock") int afterStock);
}
