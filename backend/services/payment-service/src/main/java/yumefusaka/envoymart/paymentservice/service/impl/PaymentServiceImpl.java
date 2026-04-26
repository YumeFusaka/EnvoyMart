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

    private final PaymentMapper paymentMapper;
    private final RabbitTemplate rabbitTemplate;

    public PaymentServiceImpl(PaymentMapper paymentMapper, RabbitTemplate rabbitTemplate) {
        this.paymentMapper = paymentMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        PaymentEntity entity = new PaymentEntity();
        entity.setOrderId(request.getOrderId());
        entity.setOrderNo(request.getOrderNo());
        entity.setUserId(request.getUserId());
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

        entity.setStatus(request.getStatus());
        entity.setTransactionNo(request.getTransactionNo());
        if ("SUCCESS".equals(request.getStatus())) {
            entity.setPaidAt(LocalDateTime.now());
        }
        paymentMapper.updateById(entity);

        // 发布支付完成事件，驱动后续流程（订单发货、通知等）
        if ("SUCCESS".equals(request.getStatus())) {
            rabbitTemplate.convertAndSend(ORDER_EXCHANGE, PAYMENT_COMPLETED_KEY,
                    new PaymentCompletedEventPayload(entity.getOrderId(), entity.getOrderNo(),
                            request.getTransactionNo(), entity.getAmount(), entity.getPaidAt()));
            log.info("支付成功事件已发布: orderNo={}, txNo={}", entity.getOrderNo(), request.getTransactionNo());
        }

        return toResponse(entity);
    }

    @Override
    public PaymentResponse getPayment(Long orderId) {
        PaymentEntity entity = paymentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, orderId));
        if (entity == null) {
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
