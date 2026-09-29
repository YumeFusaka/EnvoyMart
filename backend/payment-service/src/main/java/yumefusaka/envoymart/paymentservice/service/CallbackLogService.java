package yumefusaka.envoymart.paymentservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.paymentservice.entity.PaymentCallbackLogEntity;
import yumefusaka.envoymart.paymentservice.mapper.PaymentCallbackLogMapper;
import yumefusaka.envoymart.paymentservice.model.PaymentCallbackRequest;

/**
 * 回调流水落库。
 * <p>
 * <b>独立成一个 bean 并且用 {@code REQUIRES_NEW}</b>，是因为它原先写在
 * {@code processCallback} 的事务里：那条路径上「重复回调」「流水号不一致」
 * 都会抛异常，异常一回滚，刚插入的流水就一起没了 —— 而那些恰恰是最需要留痕的请求。
 * <p>
 * 同类内的方法调用不会经过 Spring 代理，{@code REQUIRES_NEW} 不会生效，
 * 所以这里必须是独立的 bean，不能只是 PaymentServiceImpl 里的一个方法。
 */
@Slf4j
@Service
public class CallbackLogService {

    private final PaymentCallbackLogMapper callbackLogMapper;
    private final ObjectMapper objectMapper;

    public CallbackLogService(PaymentCallbackLogMapper callbackLogMapper, ObjectMapper objectMapper) {
        this.callbackLogMapper = callbackLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记一条回调流水。**独立事务**：调用方随后抛出的异常不该把它回滚掉。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PaymentCallbackRequest request, String signature, boolean verified) {
        try {
            PaymentCallbackLogEntity log = new PaymentCallbackLogEntity();
            log.setOrderNo(request.getOrderId() == null ? null : String.valueOf(request.getOrderId()));
            log.setTransactionNo(request.getTransactionNo());
            log.setStatus(request.getStatus());
            log.setPayload(objectMapper.writeValueAsString(request));
            log.setSignature(signature);
            log.setVerified(verified ? 1 : 0);
            log.setCreatedAt(Times.now());
            callbackLogMapper.insert(log);
        } catch (Exception e) {
            // 留痕本身失败不该影响主流程。但它是资金链路的观测点，要吵一声
            log.warn("[Payment] 回调流水写入失败", e);
        }
    }
}
