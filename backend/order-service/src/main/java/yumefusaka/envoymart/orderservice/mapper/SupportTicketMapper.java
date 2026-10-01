package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.orderservice.entity.SupportTicketEntity;
import yumefusaka.envoymart.orderservice.model.TicketSummary;

@Mapper
public interface SupportTicketMapper extends BaseMapper<SupportTicketEntity> {

    /**
     * 一个用户的工单计数，六个数字一次查完。
     * <p>
     * 用一条聚合 SQL 而不是「查出来在 Java 里数」或者五个 {@code count(*)}：
     * 前者要按工单条数搬运数据，后者会让页签与角标取到五个不同时刻的快照——
     * 而"全部"和四个状态之和对不上的界面，用户一眼就能看见。
     * <p>
     * {@code count(case when ... then 1 end)} 而不是 {@code sum(...)}：
     * 用户一条工单都没有时，{@code sum} 返回的是 {@code NULL} 而不是 0。
     */
    @Select("""
            select
              count(*) as total,
              count(case when status = 'OPEN' then 1 end) as open,
              count(case when status = 'PROCESSING' then 1 end) as processing,
              count(case when status = 'RESOLVED' then 1 end) as resolved,
              count(case when status = 'CLOSED' then 1 end) as closed,
              count(case when status <> 'CLOSED'
                          and (last_reply_by = 'ADMIN' or status = 'RESOLVED') then 1 end) as awaiting_me
            from support_ticket
            where user_id = #{userId}
            """)
    TicketSummary countByUser(@Param("userId") String userId);
}
