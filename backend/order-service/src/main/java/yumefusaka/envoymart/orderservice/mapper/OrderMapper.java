package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.model.OrderStatusCount;

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
}
