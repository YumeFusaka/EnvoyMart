package yumefusaka.envoymart.paymentservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String ORDER_EXCHANGE = "envoymart.order";
    private static final String PAYMENT_COMPLETED_KEY = "payment.completed";

    /** 终态集合：进入其中任一状态后不再接受任何变更 */
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of("SUCCESS", "FAILED");

    private final PaymentMapper paymentMapper;
    private final RabbitTemplate rabbitTemplate;

    public PaymentServiceImpl(PaymentMapper paymentMapper, RabbitTemplate rabbitTemplate) {
        this.paymentMapper = paymentMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(String userId, CreatePaymentRequest request) {
        // 幂等：一个订单只应有一张支付单。
        // 重复创建时写入侧毫无阻碍，读取侧的 selectOne 却会因为多行直接抛
        // TooManyResultsException——**不加约束的写入会把读取路径打挂**，
        // 而且只有真正建过两次单才会暴露。
        PaymentEntity existing = paymentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, request.getOrderId()));
        if (existing != null) {
            log.info("订单 {} 已有支付单，直接复用: status={}", request.getOrderId(), existing.getStatus());
            return toResponse(existing);
        }

        PaymentEntity entity = new PaymentEntity();
        entity.setOrderId(request.getOrderId());
        entity.setOrderNo(request.getOrderNo());
        // 归属以网关注入的身份为准，不用请求体里的值——请求体是调用方可改的
        entity.setUserId(userId);
        entity.setAmount(request.getAmount());
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        paymentMapper.insert(entity);
        log.info("创建支付记录: orderNo={}, amount={}", request.getOrderNo(), request.getAmount());
        return toResponse(entity);
    }

    @Override
    @Transactional
    public PaymentResponse processCallback(PaymentCallbackRequest request) {
        PaymentEntity entity = paymentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, request.getOrderId()));
        if (entity == null) {
            throw new IllegalArgumentException("支付记录不存在");
        }

        String incoming = request.getStatus();
        if (!TERMINAL_STATUSES.contains(incoming)) {
            throw new IllegalArgumentException("不支持的支付状态：" + incoming);
        }

        // 终态不可再变更。支付渠道是 at-least-once 投递，重复回调是常态：
        // 同一结果重复到达要幂等吞掉，相反的结果则必须拒绝——
        // 否则一次迟到的 FAILED 就能把已成功的支付改回失败，钱收了、单却是未支付。
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            if (entity.getStatus().equals(incoming)) {
                log.info("[Payment] 重复回调已忽略 orderNo={} status={}", entity.getOrderNo(), incoming);
                return toResponse(entity);
            }
            log.warn("[Payment] 拒绝终态回退 orderNo={} {} -> {}", entity.getOrderNo(), entity.getStatus(), incoming);
            throw new IllegalStateException("支付已处于终态 " + entity.getStatus() + "，不接受变更为 " + incoming);
        }

        // 流水号一致性：同一笔支付不该出现两个不同的渠道流水号
        if (entity.getTransactionNo() != null && !entity.getTransactionNo().equals(request.getTransactionNo())) {
            throw new IllegalStateException("支付流水号不一致，拒绝处理");
        }

        entity.setStatus(incoming);
        entity.setTransactionNo(request.getTransactionNo());
        if ("SUCCESS".equals(incoming)) {
            entity.setPaidAt(LocalDateTime.now());
        }
        paymentMapper.updateById(entity);

        // 只在这里发布：重复回调已在前面的幂等分支返回，不会重复投递下游
        if ("SUCCESS".equals(incoming)) {
            rabbitTemplate.convertAndSend(ORDER_EXCHANGE, PAYMENT_COMPLETED_KEY,
                    new PaymentCompletedEventPayload(entity.getOrderId(), entity.getOrderNo(),
                            request.getTransactionNo(), entity.getAmount(), entity.getPaidAt()));
            log.info("支付成功事件已发布: orderNo={}, txNo={}", entity.getOrderNo(), request.getTransactionNo());
        }

        return toResponse(entity);
    }

    @Override
    public PaymentResponse getPayment(String userId, Long orderId) {
        // 带归属查询：只按 orderId 查会让任何人遍历订单号读到他人的金额与流水号
        PaymentEntity entity = paymentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, orderId)
                        .eq(PaymentEntity::getUserId, userId));
        if (entity == null) {
            // 不区分"不存在"与"不属于你"，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("支付记录不存在");
        }
        return toResponse(entity);
    }

    private PaymentResponse toResponse(PaymentEntity entity) {
        return PaymentResponse.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .orderNo(entity.getOrderNo())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .transactionNo(entity.getTransactionNo())
                .paidAt(entity.getPaidAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private record PaymentCompletedEventPayload(Long orderId, String orderNo, String transactionNo,
                                                java.math.BigDecimal amount, LocalDateTime paidAt) {
    }
}
