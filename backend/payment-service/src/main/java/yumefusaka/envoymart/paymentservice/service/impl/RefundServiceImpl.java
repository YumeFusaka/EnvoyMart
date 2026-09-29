package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.entity.RefundEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.mapper.RefundMapper;
import yumefusaka.envoymart.contract.RefundRequest;
import yumefusaka.envoymart.contract.RefundResponse;
import yumefusaka.envoymart.paymentservice.service.RefundService;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class RefundServiceImpl implements RefundService {

    private static final String PAY_SUCCESS = "SUCCESS";
    private static final String REFUND_SUCCESS = "SUCCESS";

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;

    public RefundServiceImpl(PaymentMapper paymentMapper, RefundMapper refundMapper) {
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
    }

    @Override
    @Transactional
    public RefundResponse refund(String userId, RefundRequest request) {
        PaymentEntity payment = paymentMapper.selectByOrderIdForUpdate(request.getOrderId());
        // 不区分「不存在」与「不属于你」，避免成为订单号存在性的探测接口
        if (payment == null || !payment.getUserId().equals(userId)) {
            throw new IllegalArgumentException("支付记录不存在");
        }
        return doRefund(payment, request.getAfterSaleId(), request.getAmount(), request.getReason());
    }

    @Override
    @Transactional
    public RefundResponse refundForOrder(Long orderId, Long afterSaleId, Long amount, String reason) {
        PaymentEntity payment = paymentMapper.selectByOrderIdForUpdate(orderId);
        if (payment == null) {
            throw new IllegalArgumentException("支付记录不存在");
        }
        return doRefund(payment, afterSaleId, amount, reason);
    }

    @Override
    public List<RefundResponse> listByOrder(String userId, Long orderId) {
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, orderId)
                .eq(PaymentEntity::getUserId, userId));
        if (payment == null) {
            throw new IllegalArgumentException("支付记录不存在");
        }
        return refundMapper.selectList(new LambdaQueryWrapper<RefundEntity>()
                        .eq(RefundEntity::getOrderId, orderId)
                        .orderByDesc(RefundEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 真正执行退款。
     * <p>
     * <b>调用前必须已持有支付单的行锁</b>（{@code selectByOrderIdForUpdate}）——
     * 这里是「读已退金额 → 判断够不够 → 写退款单」三步，没有锁的话并发请求会各算各的。
     */
    private RefundResponse doRefund(PaymentEntity payment, Long afterSaleId, Long amount, String reason) {
        if (!PAY_SUCCESS.equals(payment.getStatus())) {
            throw new IllegalStateException("只有支付成功的订单可以退款，当前支付状态：" + payment.getStatus());
        }

        Long refundedSum = refundMapper.sumRefundedAmount(payment.getId());
        long alreadyRefunded = refundedSum == null ? 0L : refundedSum;
        long refundable = payment.getAmount() - alreadyRefunded;
        if (refundable <= 0) {
            throw new IllegalStateException("该支付单已全额退款");
        }

        long requestAmount = amount == null ? refundable : amount;
        if (requestAmount <= 0) {
            throw new IllegalArgumentException("退款金额必须大于 0");
        }
        if (requestAmount > refundable) {
            throw new IllegalStateException("退款金额超出可退额度，最多可退 " + refundable + " 分");
        }

        RefundEntity entity = new RefundEntity();
        entity.setRefundNo(generateRefundNo());
        entity.setPaymentId(payment.getId());
        entity.setOrderId(payment.getOrderId());
        entity.setAfterSaleId(afterSaleId);
        entity.setUserId(payment.getUserId());
        entity.setAmount(requestAmount);
        // 本项目没有对接真实渠道，所以退款直接置为成功。
        // **真实对接时这里必须是 PENDING**，等渠道的回调或主动查询确认后再转 SUCCESS ——
        // 直接置成功会让「退款失败但系统以为退了」成为常态，那是最难查的一类资金问题。
        entity.setStatus(REFUND_SUCCESS);
        entity.setReason(reason);
        entity.setCreatedAt(Times.now());
        entity.setRefundedAt(Times.now());
        refundMapper.insert(entity);

        log.info("[Refund] 退款完成 orderNo={} amount={}分 累计已退={}/{}",
                payment.getOrderNo(), requestAmount, alreadyRefunded + requestAmount, payment.getAmount());
        return toResponse(entity);
    }

    private String generateRefundNo() {
        return "RF" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private RefundResponse toResponse(RefundEntity entity) {
        return RefundResponse.builder()
                .id(entity.getId())
                .refundNo(entity.getRefundNo())
                .orderId(entity.getOrderId())
                .afterSaleId(entity.getAfterSaleId())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .reason(entity.getReason())
                .channelRefundNo(entity.getChannelRefundNo())
                .createdAt(entity.getCreatedAt())
                .refundedAt(entity.getRefundedAt())
                .build();
    }
}
