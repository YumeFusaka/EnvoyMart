package yumefusaka.envoymart.paymentservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.paymentservice.entity.RefundEntity;

@Mapper
public interface RefundMapper extends BaseMapper<RefundEntity> {

    /**
     * 某张支付单的已退款总额（只统计成功的）。
     * <p>
     * <b>必须是数据库聚合而不是「查出来在内存里加」</b>：后者在并发退款下会各算各的，
     * 两次各退 60% 都能通过「不超过 100%」的检查，合起来退了 120%。
     * 金额路径上的校验要落到一条 SQL 上，由数据库给出唯一答案。
     */
    @Select("select coalesce(sum(amount), 0) from refund "
            + "where payment_id = #{paymentId} and status = 'SUCCESS'")
    Long sumRefundedAmount(@Param("paymentId") Long paymentId);
}
