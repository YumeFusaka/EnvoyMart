package yumefusaka.envoymart.orderservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserTradeGuardMapper {
    @Select("select user_id,revision from order_user_trade_guard where user_id=#{userId} for update")
    java.util.Map<String, Object> lock(@Param("userId") String userId);

    @Update("update order_user_trade_guard set revision=revision+1 where user_id=#{userId}")
    int advance(@Param("userId") String userId);
}
