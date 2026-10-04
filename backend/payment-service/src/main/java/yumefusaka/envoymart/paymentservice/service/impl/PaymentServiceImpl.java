package yumefusaka.envoymart.paymentservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.paymentservice.client.OrderClient;
import yumefusaka.envoymart.paymentservice.entity.PaymentEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentMapper;
import yumefusaka.envoymart.paymentservice.model.CreatePaymentRequest;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;
import yumefusaka.envoymart.paymentservice.model.PaymentResponse;
import yumefusaka.envoymart.paymentservice.mq.OutboxWriter;
import yumefusaka.envoymart.paymentservice.service.CallbackLogService;
import yumefusaka.envoymart.paymentservice.service.PaymentService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String ORDER_EXCHANGE = "envoymart.order";
    private static final String PAYMENT_COMPLETED_KEY = "payment.completed";

    private static final String PAY_PENDING = "PENDING";
    private static final String PAY_SUCCESS = "SUCCESS";
    private static final String PAY_FAILED = "FAILED";
    private static final String MOCK_CHANNEL = "MOCK";

    /** 订单服务里「待支付」的状态名。只有它允许建支付单 */
    private static final String ORDER_STATUS_CREATED = "CREATED";

    /** 终态：进入其中任一状态后不再接受任何变更 */
    private static final Set<String> TERMINAL_STATUSES = Set.of(PAY_SUCCESS, PAY_FAILED);

    private final PaymentMapper paymentMapper;
    private final CallbackLogService callbackLogService;
    private final OutboxWriter outboxWriter;
    private final OrderClient orderClient;

    public PaymentServiceImpl(PaymentMapper paymentMapper,
                              CallbackLogService callbackLogService,
                              OutboxWriter outboxWriter,
                              OrderClient orderClient) {
        this.paymentMapper = paymentMapper;
        this.callbackLogService = callbackLogService;
        this.outboxWriter = outboxWriter;
        this.orderClient = orderClient;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(String userId, CreatePaymentRequest request) {
        // 金额、订单号、存在性全部以订单服务为准，不采信请求体
        OrderResponse order = requireOrder(userId, request.getOrderId());

        // 幂等：一个订单只应有一张支付单。
        // 重复创建时写入侧毫无阻碍，读取侧的 selectOne 却会因为多行直接抛
        // TooManyResultsException —— **不加约束的写入会把读取路径打挂**，
        // 而且只有真正建过两次单才会暴露（表上另有唯一索引兜底）
        PaymentEntity existing = paymentMapper.selectOne(
                new LambdaQueryWrapper<PaymentEntity>().eq(PaymentEntity::getOrderId, order.getId()));
        if (existing != null) {
            // 归属校验不能省：只按 orderId 查，会把别人的支付单（金额、流水号、状态）
            // 原样返回给当前用户
            if (!userId.equals(existing.getUserId())) {
                throw new IllegalStateException("该订单已存在支付单");
            }
            log.info("订单 {} 已有支付单，直接复用: status={}", order.getId(), existing.getStatus());
            return toResponse(existing, order);
        }

        if (!ORDER_STATUS_CREATED.equals(order.getStatus())) {
            throw new IllegalStateException("订单当前状态不可支付：" + order.getStatus());
        }
        // 过期不建单：关单任务随时可能把它关掉，建了也是一张永远付不了的支付单
        if (order.getExpireAt() != null && order.getExpireAt().isBefore(Times.now())) {
            throw new IllegalStateException("订单已超时未支付，请重新下单");
        }

        PaymentEntity entity = new PaymentEntity();
        entity.setPaymentNo(generatePaymentNo());
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        // 归属以网关注入的身份为准，不用请求体里的值 —— 请求体是调用方可改的
        entity.setUserId(userId);
        // 收 payAmount 而不是 totalAmount：后者是商品总额，
        // 真实应收的是「总额 + 运费 - 优惠」
        entity.setAmount(order.getPayAmount() == null ? order.getTotalAmount() : order.getPayAmount());
        entity.setChannel(request.getChannel() == null || request.getChannel().isBlank()
                ? MOCK_CHANNEL : request.getChannel());
        entity.setPayType(request.getPayType());
        entity.setStatus(PAY_PENDING);
        entity.setCreatedAt(Times.now());
        entity.setUpdatedAt(Times.now());
        paymentMapper.insert(entity);
        log.info("创建支付单: orderNo={}, amount={}分, channel={}（金额取自订单服务）",
                order.getOrderNo(), entity.getAmount(), entity.getChannel());
        return toResponse(entity, order);
    }

    @Override
    @Transactional
    public PaymentResponse processCallback(PaymentCallbackRequest request) {
        recordCallback(request, null, true);

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
        // 同一结果重复到达要幂等吞掉，相反的结果则必须拒绝 ——
        // 否则一次迟到的 FAILED 就能把已成功的支付改回失败，钱收了、单却是未支付
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            if (entity.getStatus().equals(incoming)) {
                log.info("[Payment] 重复回调已忽略 orderNo={} status={}", entity.getOrderNo(), incoming);
                return toResponse(entity, null);
            }
            log.warn("[Payment] 拒绝终态回退 orderNo={} {} -> {}",
                    entity.getOrderNo(), entity.getStatus(), incoming);
            throw new IllegalStateException("支付已处于终态 " + entity.getStatus() + "，不接受变更为 " + incoming);
        }

        // 流水号一致性：同一笔支付不该出现两个不同的渠道流水号
        if (entity.getTransactionNo() != null && !entity.getTransactionNo().equals(request.getTransactionNo())) {
            throw new IllegalStateException("支付流水号不一致，拒绝处理");
        }

        // 落库改成条件更新，把「读到的状态」也写进 where，由数据库裁决并发。
        // 上面那段终态检查是纯内存判断，挡不住**并发**回调：两个回调都读到 PENDING、
        // 双双通过检查、都写库，最后一次写赢 —— 迟到的 FAILED 依然能把 SUCCESS 覆盖掉
        String previous = entity.getStatus();
        int updated = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentEntity>()
                .eq(PaymentEntity::getOrderId, request.getOrderId())
                .eq(PaymentEntity::getStatus, previous)
                .set(PaymentEntity::getStatus, incoming)
                .set(PaymentEntity::getTransactionNo, request.getTransactionNo())
                .set(PaymentEntity::getUpdatedAt, Times.now())
                .set(PAY_SUCCESS.equals(incoming), PaymentEntity::getPaidAt, Times.now()));
        if (updated == 0) {
            // 并发回调抢先改了状态：重新读一次，同一结果按幂等吞掉，相反的结果拒绝
            PaymentEntity latest = paymentMapper.selectOne(
                    new LambdaQueryWrapper<PaymentEntity>()
                            .eq(PaymentEntity::getOrderId, request.getOrderId()));
            if (latest != null && incoming.equals(latest.getStatus())) {
                log.info("[Payment] 并发重复回调已忽略 orderNo={} status={}", latest.getOrderNo(), incoming);
                return toResponse(latest, null);
            }
            log.warn("[Payment] 支付状态被并发变更 orderNo={} {} -> {} 被拒",
                    entity.getOrderNo(), previous, incoming);
            throw new IllegalStateException("支付状态已被并发变更，请稍后查询");
        }
        entity.setStatus(incoming);
        entity.setTransactionNo(request.getTransactionNo());
        if (PAY_SUCCESS.equals(incoming)) {
            entity.setPaidAt(Times.now());
        }

        // 事件发布改为**写发件箱，与状态变更同一个事务**（U60）。
        //
        // 原先它是在这个方法体内直接 convertAndSend。Spring 的事务提交发生在方法
        // 返回之后，而发送是立即生效的网络动作 —— 存在这个窗口：事件已经投出去、
        // 事务还没提交，此刻崩溃或提交失败，钱的状态没有落库、下游却已经按
        // 「支付完成」转了订单。资金路径上最贵的一种分叉。
        //
        // 现在只写 event_outbox（随事务一起提交或回滚），由 OutboxRelay 在提交后
        // 扫描投递。并发重复回调仍然由上面那条条件更新拦住：updated=0 的分支
        // 直接抛异常退出，走不到这里，发件箱里不会多出一行。
        if (PAY_SUCCESS.equals(incoming)) {
            outboxWriter.append(PAYMENT_COMPLETED_KEY, request.getOrderId() + "",
                    ORDER_EXCHANGE, PAYMENT_COMPLETED_KEY,
                    new PaymentCompletedEventPayload(entity.getOrderId(), entity.getOrderNo(),
                            request.getTransactionNo(), entity.getAmount(), entity.getPaidAt()));
            log.info("支付成功事件已登记待发布: orderNo={}, txNo={}", entity.getOrderNo(), request.getTransactionNo());
        }

        return toResponse(entity, null);
    }


    @Override
    public PaymentResponse getPayment(String userId, Long orderId) {
        // 带归属查询：只按 orderId 查会让任何人遍历订单号读到他人的金额与流水号
        PaymentEntity entity = paymentMapper.selectOne(
                new LambdaQueryWrapper<PaymentEntity>()
                        .eq(PaymentEntity::getOrderId, orderId)
                        .eq(PaymentEntity::getUserId, userId));
        if (entity == null) {
            // 不区分「不存在」与「不属于你」，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("支付记录不存在");
        }
        return toResponse(entity, null);
    }

    @Override
    public void recordRejectedCallback(PaymentCallbackRequest request, String signature) {
        recordCallback(request, signature, false);
    }

    /**
     * 回调流水落库。
     * <p>
     * <b>验签失败的也要记</b>：被伪造的回调是有价值的排查线索，只记录成功日志
     * 等于把线索丢掉。原始报文一并保留 —— 出现「渠道说回调了但订单没变」这类问题时，
     * 唯一能自证的就是它。
     */
    private void recordCallback(PaymentCallbackRequest request, String signature, boolean verified) {
        callbackLogService.record(request, signature, verified);
    }

    /**
     * 取订单并要求它确实属于当前用户。
     * <p>
     * 注意判的是<b>业务码</b>而不是 HTTP 状态码：order-service 的异常会被统一包成
     * HTTP 200 + {@code code=500}，而 Feign 只按状态码判断成败、不会抛异常。
     */
    private OrderResponse requireOrder(String userId, Long orderId) {
        Result<OrderResponse> result;
        try {
            result = orderClient.getOrder(userId, orderId);
        } catch (Exception e) {
            log.warn("[Payment] 查询订单失败 orderId={}: {}", orderId, e.getMessage());
            throw new IllegalStateException("订单服务暂时不可用，请稍后再试");
        }
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            // 不区分「不存在」与「不属于你」，避免成为订单号存在性的探测接口
            throw new IllegalArgumentException("订单不存在");
        }
        return result.getData();
    }

    private String generatePaymentNo() {
        return "PY" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private PaymentResponse toResponse(PaymentEntity entity, OrderResponse order) {
        return PaymentResponse.builder()
                .id(entity.getId())
                .paymentNo(entity.getPaymentNo())
                .orderId(entity.getOrderId())
                .orderNo(entity.getOrderNo())
                .amount(entity.getAmount())
                .channel(entity.getChannel())
                .payType(entity.getPayType())
                .status(entity.getStatus())
                .transactionNo(entity.getTransactionNo())
                .paidAt(entity.getPaidAt())
                // 支付截止时间来自订单：前端据此显示倒计时，不必再查一次订单
                .expireAt(order == null ? null : order.getExpireAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /** 发到 MQ 的载荷。金额用「分」，与全链路保持一致 */
    private record PaymentCompletedEventPayload(Long orderId, String orderNo, String transactionNo,
                                                Long amount, LocalDateTime paidAt) {
    }
}
