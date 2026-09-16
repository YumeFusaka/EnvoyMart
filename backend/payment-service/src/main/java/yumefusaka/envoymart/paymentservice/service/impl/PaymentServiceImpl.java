package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.paymentservice.client.OrderClient;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.paymentservice.model.OrderSnapshot;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String ORDER_EXCHANGE = "envoymart.order";
    private static final String PAYMENT_COMPLETED_KEY = "payment.completed";

    /** 终态集合：进入其中任一状态后不再接受任何变更 */
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");

    /** 可以发起支付的状态。订单建单即 DELIVERING，已支付或已取消的都不该再建支付单 */
    private static final Set<String> PAYABLE_STATUSES = Set.of("PENDING", "DELIVERING");

    private final PaymentMapper paymentMapper;
    private final RabbitTemplate rabbitTemplate;
    private final OrderClient orderClient;

    public PaymentServiceImpl(PaymentMapper paymentMapper, RabbitTemplate rabbitTemplate,
                              OrderClient orderClient) {
        this.paymentMapper = paymentMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.orderClient = orderClient;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(String userId, CreatePaymentRequest request) {
        // 金额、订单号、存在性全部以订单服务为准，不采信请求体
        OrderSnapshot order = requireOrder(userId, request.getOrderId());

        // 幂等：一个订单只应有一张支付单。
        // 重复创建时写入侧毫无阻碍，读取侧的 selectOne 却会因为多行直接抛
        // TooManyResultsException——**不加约束的写入会把读取路径打挂**，
        // 而且只有真正建过两次单才会暴露。
        PaymentEntity existing = paymentMapper.selectOne(
                new LambdaQueryWrapper<PaymentEntity>().eq(PaymentEntity::getOrderId, order.getId()));
        if (existing != null) {
            // 归属校验不能省：只按 orderId 查，会把别人的支付单（金额、流水号、状态）
            // 原样返回给当前用户。读接口一直是带 userId 过滤的，同一份归属规则
            // 不该在两个入口给出不同答案。
            if (!userId.equals(existing.getUserId())) {
                throw new IllegalStateException("该订单已存在支付单");
            }
            log.info("订单 {} 已有支付单，直接复用: status={}", order.getId(), existing.getStatus());
            return toResponse(existing);
        }

        if (!PAYABLE_STATUSES.contains(order.getStatus())) {
            throw new IllegalStateException("订单当前状态不可支付：" + order.getStatus());
        }

        PaymentEntity entity = new PaymentEntity();
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        // 归属以网关注入的身份为准，不用请求体里的值——请求体是调用方可改的
        entity.setUserId(userId);
        entity.setAmount(order.getTotalAmount());
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        paymentMapper.insert(entity);
        log.info("创建支付记录: orderNo={}, amount={}（金额取自订单服务）",
                order.getOrderNo(), order.getTotalAmount());
        return toResponse(entity);
    }

    @Override
    @Transactional
    public PaymentResponse processCallback(PaymentCallbackRequest request) {
        PaymentEntity entity = paymentMapper.selectOne(
                new LambdaQueryWrapper<PaymentEntity>()
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

        // 落库改成条件更新，把「读到的状态」也写进 where，由数据库裁决并发。
        // 上面那段终态检查是纯内存判断，挡不住<b>并发</b>回调：两个回调都读到 PENDING、
        // 双双通过检查、都写库，最后一次写赢——迟到的 FAILED 依然能把 SUCCESS 覆盖掉，
        // 而且 payment.completed 会被投递两次。渠道重试并发到达即可触发。
        String previous = entity.getStatus();
        int updated = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, request.getOrderId())
                .eq(PaymentEntity::getStatus, previous)
                .set(PaymentEntity::getStatus, incoming)
                .set(PaymentEntity::getTransactionNo, request.getTransactionNo())
                .set("SUCCESS".equals(incoming), PaymentEntity::getPaidAt, LocalDateTime.now()));
        if (updated == 0) {
            // 并发回调抢先改了状态：重新读一次，同一结果按幂等吞掉，相反的结果拒绝
            PaymentEntity latest = paymentMapper.selectOne(
                    new LambdaQueryWrapper<PaymentEntity>()
                            .eq(PaymentEntity::getOrderId, request.getOrderId()));
            if (latest != null && incoming.equals(latest.getStatus())) {
                log.info("[Payment] 并发重复回调已忽略 orderNo={} status={}", latest.getOrderNo(), incoming);
                return toResponse(latest);
            }
            log.warn("[Payment] 支付状态被并发变更 orderNo={} {} -> {} 被拒",
                    entity.getOrderNo(), previous, incoming);
            throw new IllegalStateException("支付状态已被并发变更，请稍后查询");
        }
        entity.setStatus(incoming);
        entity.setTransactionNo(request.getTransactionNo());
        if ("SUCCESS".equals(incoming)) {
            entity.setPaidAt(LocalDateTime.now());
        }

        // 只在这里发布：重复回调已在前面的幂等分支返回，并发重复则在 updated=0 分支返回，
        // 两条路径都不会重复投递下游
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
                new LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, orderId)
                        .eq(PaymentEntity::getUserId, userId));
        if (entity == null) {
            // 不区分"不存在"与"不属于你"，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("支付记录不存在");
        }
        return toResponse(entity);
    }

    /**
     * 取订单并要求它确实属于当前用户。
     * <p>
     * 注意判的是<b>业务码</b>而不是 HTTP 状态码：order-service 的异常会被统一包成
     * HTTP 200 + {@code code=500}，而 Feign 只按状态码判断成败、不会抛异常。
     * 这里 catch 兜的是连不上/超时一类的传输失败，业务失败靠下面的 code 判断。
     */
    private OrderSnapshot requireOrder(String userId, Long orderId) {
        Result<OrderSnapshot> result;
        try {
            result = orderClient.getOrder(userId, orderId);
        } catch (Exception e) {
            log.warn("[Payment] 查询订单失败 orderId={}: {}", orderId, e.getMessage());
            throw new IllegalStateException("订单服务暂时不可用，请稍后再试");
        }
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            // 不区分"不存在"与"不属于你"，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("订单不存在");
        }
        return result.getData();
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
