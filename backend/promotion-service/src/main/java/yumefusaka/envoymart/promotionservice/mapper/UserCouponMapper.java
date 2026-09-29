package yumefusaka.envoymart.promotionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.promotionservice.entity.UserCouponEntity;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCouponEntity> {

    /**
     * 核销一张券。
     * <p>
     * 带上 {@code status = 'UNUSED'} 与 {@code expire_at &gt; now()} 两个条件，
     * 由数据库裁决并发：同一张券被两个并发请求同时核销时，只有一个的 affected=1，
     * 另一个直接失败。**折扣只能减一次钱**，这条约束必须落在 SQL 上。
     */
    @Update("update user_coupon set status = 'USED', order_no = #{orderNo}, used_at = now() "
            + "where id = #{id} and user_id = #{userId} "
            + "and status = 'UNUSED' and expire_at > now()")
    int redeem(@Param("id") Long id, @Param("userId") String userId, @Param("orderNo") String orderNo);
}
