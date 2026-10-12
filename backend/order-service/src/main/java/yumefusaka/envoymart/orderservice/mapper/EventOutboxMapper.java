package yumefusaka.envoymart.orderservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.orderservice.entity.EventOutboxEntity;

public interface EventOutboxMapper extends BaseMapper<EventOutboxEntity> {
    @Select("select * from event_outbox where id=#{id} and sent_at is null for update")
    EventOutboxEntity lockPending(@Param("id") Long id);

    @Update("update event_outbox set sent_at=current_timestamp,attempts=attempts+1,last_error=null where id=#{id} and sent_at is null")
    int markSent(@Param("id") Long id);

    @Update("update event_outbox set attempts=attempts+1,last_error=#{error} where id=#{id} and sent_at is null")
    int markFailed(@Param("id") Long id, @Param("error") String error);
}
