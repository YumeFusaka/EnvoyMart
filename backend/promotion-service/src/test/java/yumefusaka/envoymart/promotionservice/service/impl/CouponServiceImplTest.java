package yumefusaka.envoymart.promotionservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.contract.RedeemItem;
import yumefusaka.envoymart.contract.RedeemRequest;
import yumefusaka.envoymart.promotionservice.entity.CouponEntity;
import yumefusaka.envoymart.promotionservice.entity.UserCouponEntity;
import yumefusaka.envoymart.promotionservice.mapper.CouponMapper;
import yumefusaka.envoymart.promotionservice.mapper.UserCouponMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 券核销的作用域与折扣计算 —— 减钱的方向只有一个：宁可少减，不能多减。
 * <p>
 * 钉住的核心事实：门槛与折扣的基数都是<b>券作用范围内商品的小计</b>，不是订单总额，
 * 也不是被券「蹭」上的任意金额。范围外的商品既不能帮用户凑门槛、也不能被折扣。
 * 数据不自洽（scopeIds 非法）时拒绝而不是猜——猜错的方向就是多减钱。
 */
class CouponServiceImplTest {

    private CouponMapper couponMapper;
    private UserCouponMapper userCouponMapper;
    private CouponServiceImpl service;

    @BeforeEach
    void setUp() {
        couponMapper = mock(CouponMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        service = new CouponServiceImpl(couponMapper, userCouponMapper);
    }

    /** 一张属于 u1001 的未使用券，模板由测试各自给 */
    private void givenCoupon(CouponEntity coupon) {
        UserCouponEntity uc = new UserCouponEntity();
        uc.setId(1L);
        uc.setUserId("u1001");
        uc.setCouponId(coupon.getId());
        uc.setStatus("UNUSED");
        when(userCouponMapper.selectById(1L)).thenReturn(uc);
        when(couponMapper.selectById(coupon.getId())).thenReturn(coupon);
    }

    private static CouponEntity coupon(String type, Long amount, BigDecimal discount,
                                       long threshold, String scopeType, String scopeIds) {
        CouponEntity c = new CouponEntity();
        c.setId(5L);
        c.setType(type);
        c.setAmount(amount);
        c.setDiscount(discount);
        c.setThreshold(threshold);
        c.setScopeType(scopeType);
        c.setScopeIds(scopeIds);
        return c;
    }

    private static RedeemItem item(long spuId, long categoryId, long subtotal) {
        return RedeemItem.builder().spuId(spuId).categoryId(categoryId).subtotal(subtotal).build();
    }

    private static RedeemRequest request(RedeemItem... items) {
        return RedeemRequest.builder().userCouponId(1L).orderNo("YS1").items(List.of(items)).build();
    }

    @Test
    void 购物车里一件范围内商品都没有时拒绝核销() {
        givenCoupon(coupon("FIXED", 2000L, null, 10000, "CATEGORY", "2,3,4,5,6"));
        when(userCouponMapper.redeem(anyLong(), anyString(), anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.redeem("u1001", request(item(10, 14, 13800))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("该优惠券不适用于订单中的商品");
        // 拒绝发生在核销之前：券不能被消耗
        verify(userCouponMapper, never()).redeem(anyLong(), anyString(), anyString());
    }

    @Test
    void 门槛按范围内小计判定而不是订单总额() {
        givenCoupon(coupon("FIXED", 2000L, null, 10000, "CATEGORY", "2,3,4,5,6"));

        // 总额 20700 > 门槛，但范围内的只有 6900 < 10000
        assertThatThrownBy(() -> service.redeem("u1001", request(item(10, 14, 13800), item(6, 4, 6900))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("优惠券适用范围内的商品金额未达到使用门槛，还差 3100 分");
        verify(userCouponMapper, never()).redeem(anyLong(), anyString(), anyString());
    }

    @Test
    void 满减券按范围内小计抵扣范围外不参与() {
        givenCoupon(coupon("FIXED", 2000L, null, 10000, "CATEGORY", "2,3,4,5,6"));
        when(userCouponMapper.redeem(anyLong(), anyString(), anyString())).thenReturn(1);

        long deduct = service.redeem("u1001", request(item(2, 2, 12800), item(10, 14, 13800)));

        assertThat(deduct).isEqualTo(2000L);
        verify(userCouponMapper).redeem(1L, "u1001", "YS1");
    }

    @Test
    void 折扣券的折扣基数是范围内小计而不是订单总额() {
        givenCoupon(coupon("DISCOUNT", null, new BigDecimal("0.85"), 0, "CATEGORY", "2"));
        when(userCouponMapper.redeem(anyLong(), anyString(), anyString())).thenReturn(1);

        // 范围内 12800：抵扣 12800×0.15=1920；若错用总额 26600 会算出 3990
        long deduct = service.redeem("u1001", request(item(2, 2, 12800), item(10, 14, 13800)));

        assertThat(deduct).isEqualTo(1920L);
    }

    @Test
    void 全场券的基数是全部商品() {
        givenCoupon(coupon("FIXED", 2000L, null, 10000, "ALL", null));
        when(userCouponMapper.redeem(anyLong(), anyString(), anyString())).thenReturn(1);

        long deduct = service.redeem("u1001", request(item(2, 2, 12800), item(10, 14, 13800)));

        assertThat(deduct).isEqualTo(2000L);
    }

    @Test
    void scopeIds非法时按不可用拒绝而不是当成全场券() {
        givenCoupon(coupon("FIXED", 2000L, null, 0, "SPU", "abc"));

        assertThatThrownBy(() -> service.redeem("u1001", request(item(2, 2, 12800))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("该优惠券暂不可用");
        verify(userCouponMapper, never()).redeem(anyLong(), anyString(), anyString());
    }

    @Test
    void 条件更新没抢到券时核销失败() {
        givenCoupon(coupon("FIXED", 2000L, null, 0, "ALL", null));
        // 并发下另一个请求先核销了：affected=0
        when(userCouponMapper.redeem(anyLong(), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.redeem("u1001", request(item(2, 2, 12800))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("优惠券不可用（已使用或已过期）");
    }
}
