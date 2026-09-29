package yumefusaka.envoymart.promotionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.promotionservice.entity.CouponEntity;

@Mapper
public interface CouponMapper extends BaseMapper<CouponEntity> {

    /**
     * 原子领取计数 +1。
     * <p>
     * 与库存扣减同一套模式：判断与递增在同一条语句里，{@code received_count &lt; total_count}
     * 直接兜住超发。<b>不能写成「查出来判断够不够、再 set 回去」</b> ——
     * 那是读-改-写，并发领券会各自读到同一个旧值，最后一张券被发给好几个人。
     *
     * @return 影响行数，0 表示已领完
     */
    @Update("update coupon set received_count = received_count + 1 "
            + "where id = #{couponId} and received_count < total_count and status = 1")
    int incrementReceived(@Param("couponId") Long couponId);
}
