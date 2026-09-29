package yumefusaka.envoymart.paymentservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;

@Mapper
public interface PaymentMapper extends BaseMapper<PaymentEntity> {

    /**
     * 按订单号取支付单并**锁住这一行**，直到事务结束。
     * <p>
     * 退款路径必须用它而不是普通的 selectOne：「查已退金额 → 判断够不够 → 插入退款单」
     * 这三步在并发下会各算各的，两笔各退 60% 都能通过「不超过 100%」的检查。
     * 行锁把同一张支付单的退款请求串行化，第二笔读到的就是第一笔写入后的余额。
     * <p>
     * 支付本身没有这条路径的并发压力，但<b>资金路径上的校验必须由数据库裁决</b>。
     */
    @Select("select * from payment where order_id = #{orderId} for update")
    PaymentEntity selectByOrderIdForUpdate(@Param("orderId") Long orderId);
}
