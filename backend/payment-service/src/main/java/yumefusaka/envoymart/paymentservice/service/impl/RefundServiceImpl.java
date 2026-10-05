package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.util.Amounts;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.entity.RefundEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.mapper.RefundMapper;
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
    public RefundResponse refundForOrder(Long orderId, Long afterSaleId, String bizNo, Long amount, String reason) {
        PaymentEntity payment = paymentMapper.selectByOrderIdForUpdate(orderId);
        if (payment == null) {
            throw new IllegalArgumentException("支付记录不存在");
        }
        return doRefund(payment, afterSaleId, bizNo, amount, reason);
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
    private RefundResponse doRefund(PaymentEntity payment, Long afterSaleId, String bizNo,
                                    Long amount, String reason) {
        if (!PAY_SUCCESS.equals(payment.getStatus())) {
            throw new IllegalStateException("只有支付成功的订单可以退款，当前支付状态：" + payment.getStatus());
        }

        // 幂等：同一张支付单上，同一个售后单（或同一个业务键）只退一次。
        // 售后侧的重试是运营手点的动作，而「上次其实退成功了、只是响应在路上丢了」正是重试
        // 最常见的触发场景；没有这道检查，第二次会再插一条退款单、再退一笔钱，
        // 而两边的日志都写着「退款完成」，对账时才会发现。
        // 条件里必须带 payment_id：只按 afterSaleId 查的话，一条售后单的 id 就能换出
        // 别人那张支付单上的退款记录——而售后单 id 是顺序自增的，猜得到。
        // 并发安全由调用方的支付单行锁保证：两个请求在这里被串行化，
        // 后到的那个一定看得见先到的插入。
        if (afterSaleId != null) {
            RefundEntity done = refundMapper.selectOne(new LambdaQueryWrapper<RefundEntity>()
                    .eq(RefundEntity::getPaymentId, payment.getId())
                    .eq(RefundEntity::getAfterSaleId, afterSaleId)
                    .eq(RefundEntity::getStatus, REFUND_SUCCESS)
                    .last("limit 1"));
            if (done != null) {
                log.info("[Refund] 该售后单已退过款，按幂等返回原结果 afterSaleId={} refundNo={}",
                        afterSaleId, done.getRefundNo());
                return toResponse(done);
            }
        } else if (bizNo != null && !bizNo.isBlank()) {
            // 主动退款（订单取消等）没有售后单可挂，幂等靠调用方给的业务键。
            // 键由调用方按「同一件事」生成——重试必须用同一个键，否则这里什么都挡不住
            RefundEntity done = refundMapper.selectOne(new LambdaQueryWrapper<RefundEntity>()
                    .eq(RefundEntity::getPaymentId, payment.getId())
                    .eq(RefundEntity::getBizNo, bizNo)
                    .eq(RefundEntity::getStatus, REFUND_SUCCESS)
                    .last("limit 1"));
            if (done != null) {
                log.info("[Refund] 该业务键已退过款，按幂等返回原结果 bizNo={} refundNo={}",
                        bizNo, done.getRefundNo());
                return toResponse(done);
            }
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
            // 金额在库里是「分」，但这句直接给用户看，要按元展示（见 Amounts）
            throw new IllegalStateException("退款金额超出可退额度，最多可退 " + Amounts.yuan(refundable) + " 元");
        }

        RefundEntity entity = new RefundEntity();
        entity.setRefundNo(generateRefundNo());
        entity.setPaymentId(payment.getId());
        entity.setOrderId(payment.getOrderId());
        entity.setAfterSaleId(afterSaleId);
        entity.setBizNo(bizNo);
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
