package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.model.OrderStatusCount;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 某用户的订单按状态计数。
     * <p>
     * <b>一条 SQL 出全部分组</b>，不是每个页签查一次：分开查会取到几个不同时刻的快照，
     * 而「全部」对不上各类之和是一眼就能看出来的（连点两下页签数字还会跳）。
     * 分组而不是把页签的成员状态写死在 SQL 里：页签的成员关系只在
     * {@link yumefusaka.envoymart.orderservice.model.OrderTab} 里定义一次。
     */
    @Select("""
            select status, count(*) as cnt
            from shop_order
            where user_id = #{userId}
            group by status
            """)
    List<OrderStatusCount> countByStatus(@Param("userId") String userId);

    /**
     * 该用户最早一次「已收货」订单的收货时间，没有则返回 {@code null}。
     * <p>
     * 判据取 {@code received_at} 而不是 {@code created_at}：新号可以批量下单然后立刻退货，
     * 下单时间挡不住这种；收货时间要求这笔交易真的走完过。
     * <p>
     * {@code min()} 在没有匹配行时返回 {@code NULL}，映射到 {@code LocalDateTime} 就是 null，
     * 不需要额外判空 —— 这正是这里想要的结果。
     */
    @Select("""
            select min(received_at)
            from shop_order
            where user_id = #{userId} and received_at is not null
            """)
    LocalDateTime firstReceivedAt(@Param("userId") String userId);
}
