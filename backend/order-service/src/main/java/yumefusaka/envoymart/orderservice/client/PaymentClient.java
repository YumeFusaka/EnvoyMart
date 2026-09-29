package yumefusaka.envoymart.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.orderservice.model.RefundRequest;
import yumefusaka.envoymart.orderservice.model.RefundSnapshot;

/**
 * 支付服务客户端 —— 只为退款而存在。
 * <p>
 * 订单侧不直接改支付状态：退款是资金动作，必须由持有支付单的服务执行，
 * 那里有行锁、有已退金额聚合、有额度校验。绕过它自己写库，等于把那些保护全部丢掉。
 */
@FeignClient(name = "payment-service", url = "${services.payment-service-url:http://127.0.0.1:9005}")
public interface PaymentClient {

    /**
     * 按订单退款。金额不传表示退剩余全部。
     * <p>
     * 这个接口在支付服务里没有调用方身份（不是用户直接发起的），
     * 表达的是「这笔订单的钱要还回去」。
     */
    @PostMapping("/payments/refunds/internal")
    Result<RefundSnapshot> refundForOrder(@RequestBody RefundRequest request);
}
