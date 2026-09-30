package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.contract.RefundResponse;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.entity.RefundEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.mapper.RefundMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款的幂等 —— 资金路径上最容易漏掉的一道。
 * <p>
 * 售后侧的重试是运营手点的按钮，而「上次其实退成功了、只是响应在路上丢了」正是重试
 * 最常见的触发场景。没有幂等键时，第二次调用会看到可退余额还够（部分退款时必然够），
 * 于是<b>再插一条退款单、再退一笔钱</b>，而两边的日志都写着「退款完成」。
 * <p>
 * 并发安全由调用方持有的支付单行锁保证（{@code selectByOrderIdForUpdate}），
 * 所以这里只需要钉住「查了没有」这一件事。
 */
class RefundServiceImplTest {

    private PaymentMapper paymentMapper;
    private RefundMapper refundMapper;
    private RefundServiceImpl service;

    /**
     * {@code LambdaQueryWrapper} 要按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存
     * ——那份缓存平时由 Spring 容器在启动时建立，纯单元测试里没有，于是抛
     * "can not find lambda cache for this entity"。这里手工建一次。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PaymentEntity.class);
        TableInfoHelper.initTableInfo(assistant, RefundEntity.class);
    }

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        refundMapper = mock(RefundMapper.class);
        service = new RefundServiceImpl(paymentMapper, refundMapper);
    }

    @Test
    void 同一个售后单重试退款只退一次() {
        when(paymentMapper.selectByOrderIdForUpdate(9L)).thenReturn(payment(9L, 19800L));
        when(refundMapper.selectOne(any())).thenReturn(refund("RF001", 9900L, 7001L));

        RefundResponse response = service.refundForOrder(9L, 7001L, null, 9900L, "售后退款");

        assertThat(response.getRefundNo()).isEqualTo("RF001");
        verify(refundMapper, never()).insert(any(RefundEntity.class));
        // 连余额都不必再算：幂等命中时后面几步全跳过
        verify(refundMapper, never()).sumRefundedAmount(any());
    }

    @Test
    void 幂等查重必须限定在同一张支付单内() {
        when(paymentMapper.selectByOrderIdForUpdate(9L)).thenReturn(payment(9L, 19800L));
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.refundForOrder(9L, 7001L, null, 9900L, "售后退款");

        // 只按 afterSaleId 查是不行的：这个方法的另一条入口是用户侧的 /payments/refund，
        // 调用方拿别人的售后单号就能换出别人的退款记录
        assertThat(capturedLookup().getSqlSegment())
                .contains("payment_id")
                .contains("after_sale_id");
    }

    @Test
    void 没有售后单号的主动退款不做查重() {
        when(paymentMapper.selectByOrderIdForUpdate(9L)).thenReturn(payment(9L, 19800L));

        // 两个幂等键都为空的兜底路径：查重全部跳过，靠「已全额退」的余额检查兜底
        service.refundForOrder(9L, null, null, null, "订单已关闭，自动全额退款");

        verify(refundMapper, never()).selectOne(any());
        verify(refundMapper).insert(any(RefundEntity.class));
    }

    @Test
    void 同一业务键重试主动退款只退一次() {
        when(paymentMapper.selectByOrderIdForUpdate(9L)).thenReturn(payment(9L, 19800L));
        when(refundMapper.selectOne(any())).thenReturn(refundWithBizNo("RF002", 19800L, "CANCEL:OM20260930001"));

        // 订单取消退款：响应在路上丢了，订单服务拿同一个业务键重试
        RefundResponse response = service.refundForOrder(9L, null, "CANCEL:OM20260930001", null, "订单取消退款");

        assertThat(response.getRefundNo()).isEqualTo("RF002");
        verify(refundMapper, never()).insert(any(RefundEntity.class));
        verify(refundMapper, never()).sumRefundedAmount(any());
    }

    @Test
    void 业务键查重必须限定在同一张支付单内() {
        when(paymentMapper.selectByOrderIdForUpdate(9L)).thenReturn(payment(9L, 19800L));
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.refundForOrder(9L, null, "CANCEL:OM20260930001", null, "订单取消退款");

        // 与售后单号同理：业务键按订单号生成但查重不带 payment_id 的话，
        // 异常数据下会拿别的支付单上的记录当幂等命中，该退的钱退不出去
        assertThat(capturedLookup().getSqlSegment())
                .contains("payment_id")
                .contains("biz_no");
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RefundEntity> capturedLookup() {
        ArgumentCaptor<LambdaQueryWrapper<RefundEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(refundMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    private static PaymentEntity payment(long orderId, long amount) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(1L);
        entity.setOrderId(orderId);
        entity.setOrderNo("OM20260930001");
        entity.setUserId("u1001");
        entity.setAmount(amount);
        entity.setStatus("SUCCESS");
        return entity;
    }

    private static RefundEntity refund(String refundNo, long amount, Long afterSaleId) {
        RefundEntity entity = new RefundEntity();
        entity.setId(1L);
        entity.setRefundNo(refundNo);
        entity.setPaymentId(1L);
        entity.setOrderId(9L);
        entity.setAfterSaleId(afterSaleId);
        entity.setUserId("u1001");
        entity.setAmount(amount);
        entity.setStatus("SUCCESS");
        return entity;
    }

    private static RefundEntity refundWithBizNo(String refundNo, long amount, String bizNo) {
        RefundEntity entity = refund(refundNo, amount, null);
        entity.setBizNo(bizNo);
        return entity;
    }
}
