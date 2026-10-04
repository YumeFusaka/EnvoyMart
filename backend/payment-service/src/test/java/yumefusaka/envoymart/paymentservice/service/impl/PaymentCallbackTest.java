package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.paymentservice.client.OrderClient;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.mq.OutboxWriter;

import yumefusaka.envoymart.paymentservice.service.CallbackLogService;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * <p>
 * 落库走的是条件更新（{@code update ... where status = 读到的状态}），所以这里 mock 的是
 * {@code update} 的返回行数：1 表示这次回调抢到了状态迁移，0 表示并发被抢先。
 */
class PaymentCallbackTest {

    private PaymentMapper paymentMapper;
    private CallbackLogService callbackLogService;
    private OutboxWriter outboxWriter;
    private OrderClient orderClient;
    private PaymentServiceImpl service;

    /**
     * {@code LambdaUpdateWrapper} 要按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存
     * ——那份缓存平时由 Spring 容器在启动时建立，纯单元测试里没有，于是抛
     * "can not find lambda cache for this entity"。这里手工建一次。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentEntity.class);
    }

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        // 回调流水走独立事务，所以它是一个独立的 bean —— 单测里 mock 掉即可
        callbackLogService = mock(CallbackLogService.class);
        outboxWriter = mock(OutboxWriter.class);
        orderClient = mock(OrderClient.class);
        service = new PaymentServiceImpl(paymentMapper, callbackLogService, outboxWriter, orderClient);
        // 默认：条件更新命中一行（即“这次回调成功迁移了状态”）
        when(paymentMapper.update(any(), any())).thenReturn(1);
    }

    private PaymentEntity payment(String status, String transactionNo) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(1L);
        entity.setOrderId(100L);
        entity.setOrderNo("YS100");
        entity.setUserId("u1001");
        // 金额一律用「分」：资金路径上的浮点误差不只是显示问题，
        // 「退款不得超过支付金额」这类判断会因 0.1+0.2 != 0.3 而失效
        entity.setAmount(29900L);
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
        verify(outboxWriter, times(1)).append(anyString(), anyString(), anyString(), anyString(), any());
        verify(paymentMapper, times(1)).update(any(), any());
    }

    @Test
    void 重复的成功回调被幂等吞掉且不重复发布事件() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("SUCCESS", "TX-1"));

        var response = service.processCallback(callback("SUCCESS", "TX-1"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(outboxWriter, never())
                .append(anyString(), anyString(), anyString(), anyString(), any());
        verify(paymentMapper, never()).update(any(), any());
    }

    @Test
    void 迟到的失败回调不能把已成功的支付改回失败() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("SUCCESS", "TX-1"));

        assertThatThrownBy(() -> service.processCallback(callback("FAILED", "TX-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");

        verify(paymentMapper, never()).update(any(), any());
    }

    @Test
    void 同一支付出现不同流水号时拒绝处理() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", "TX-1"));

        assertThatThrownBy(() -> service.processCallback(callback("SUCCESS", "TX-OTHER")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("流水号");

        verify(paymentMapper, never()).update(any(), any());
    }

    @Test
    void 不支持的支付状态被拒绝() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", null));

        assertThatThrownBy(() -> service.processCallback(callback("REFUNDING", "TX-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的支付状态");
    }

    /**
     * 并发回调：条件更新返回 0（状态已被另一个回调抢先改掉），且当前状态与本次请求一致时，
     * 按幂等吞掉，不能重复发布事件。
     */
    @Test
    void 并发到达的相同回调只生效一次() {
        when(paymentMapper.selectOne(any())).thenReturn(payment("PENDING", null));
        // 第一次读到 PENDING，但条件更新没命中——说明另一个并发回调已经把它改成 SUCCESS 了
        when(paymentMapper.update(any(), any())).thenReturn(0);
        when(paymentMapper.selectOne(any()))
                .thenReturn(payment("PENDING", null))
                .thenReturn(payment("SUCCESS", "TX-1"));

        var response = service.processCallback(callback("SUCCESS", "TX-1"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(outboxWriter, never())
                .append(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 并发回调带来了相反的结果：条件更新没命中，且现状与本次请求不一致——必须拒绝，
     * 不能把已成功的支付覆盖成失败。
     */
    @Test
    void 并发到达的相反回调被拒绝() {
        when(paymentMapper.update(any(), any())).thenReturn(0);
        when(paymentMapper.selectOne(any()))
                .thenReturn(payment("PENDING", null))
                .thenReturn(payment("SUCCESS", "TX-1"));

        assertThatThrownBy(() -> service.processCallback(callback("FAILED", "TX-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("并发");

        verify(outboxWriter, never())
                .append(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 金额与订单号以订单服务为准，不采信请求体。
     * <p>
     * 原实现直接 {@code entity.setAmount(request.getAmount())}，实测能把 198 元的订单
     * 建成 0.01 元的支付单，也能给根本不存在的订单建单。
     */
    @Test
    void 创建支付单的金额与订单号取自订单服务() {
        OrderResponse order = OrderResponse.builder()
                .id(100L)
                .orderNo("YS-REAL")
                .totalAmount(19800L)
                // 建支付单取的是 payAmount（实付 = 总额 + 运费 - 优惠），不是商品总额
                .payAmount(19800L)
                .status("CREATED")
                .build();
        when(orderClient.getOrder(eq("u1001"), eq(100L))).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setOrderId(100L);

        var response = service.createPayment("u1001", request);

        assertThat(response.getAmount()).isEqualTo(19800L);
        assertThat(response.getOrderNo()).isEqualTo("YS-REAL");
    }

    /** 订单不属于当前用户（或不存在）时，不许建支付单 */
    @Test
    void 订单不存在时拒绝创建支付单() {
        when(orderClient.getOrder(anyString(), any())).thenReturn(Result.error(404, "订单不存在"));

        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setOrderId(999999L);

        assertThatThrownBy(() -> service.createPayment("u1001", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("订单不存在");
    }
}
