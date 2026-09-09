package yumefusaka.envoymart.paymentservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付回调的状态机与幂等 —— 资金路径的回归防线。
 * <p>
 * 支付渠道是 at-least-once 投递，重复回调是常态而非异常。原实现直接
 * {@code entity.setStatus(request.getStatus())}：同一个 SUCCESS 重复到达会重复投递下游事件，
 * 而一次迟到的 FAILED 能把已成功的支付改回失败——钱收了、单却是未支付。
 */
class PaymentCallbackTest {

    private PaymentMapper paymentMapper;
    private RabbitTemplate rabbitTemplate;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new PaymentServiceImpl(paymentMapper, rabbitTemplate);
    }

    private PaymentEntity payment(String status, String transactionNo) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(1L);
        entity.setOrderId(100L);
        entity.setOrderNo("YS100");
        entity.setUserId("u1001");
        entity.setAmount(new BigDecimal("299.00"));
        entity.setStatus(status);
        entity.setTransactionNo(transactionNo);
        return entity;
    }

    private PaymentCallbackRequest callback(String status, String transactionNo) {
        PaymentCallbackRequest request = new PaymentCallbackRequest();
        request.setOrderId(100L);
        request.setStatus(status);
        request.setTransactionNo(transactionNo);
        return request;
    }

    @Test
    void 待支付可以转为成功并发布事件() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", null));

        var response = service.processCallback(callback("SUCCESS", "TX-1"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void 重复的成功回调被幂等吞掉且不重复发布事件() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("SUCCESS", "TX-1"));

        var response = service.processCallback(callback("SUCCESS", "TX-1"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), any(Object.class));
        verify(paymentMapper, never()).updateById(any(PaymentEntity.class));
    }

    @Test
    void 迟到的失败回调不能把已成功的支付改回失败() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("SUCCESS", "TX-1"));

        assertThatThrownBy(() -> service.processCallback(callback("FAILED", "TX-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");

        verify(paymentMapper, never()).updateById(any(PaymentEntity.class));
    }

    @Test
    void 同一支付出现不同流水号时拒绝处理() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", "TX-1"));

        assertThatThrownBy(() -> service.processCallback(callback("SUCCESS", "TX-OTHER")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("流水号");

        verify(paymentMapper, never()).updateById(any(PaymentEntity.class));
    }

    @Test
    void 不支持的支付状态被拒绝() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", null));

        assertThatThrownBy(() -> service.processCallback(callback("REFUNDING", "TX-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的支付状态");
    }
}
