package yumefusaka.envoymart.paymentservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.paymentservice.model.OrderSnapshot;

/**
 * 订单查询 —— 支付单的存在性、归属与金额一律由它裁决。
 * <p>
 * 这条调用是补上的：此前 payment-service 里<b>没有任何 order-service 客户端</b>，
 * {@code services.order-service-url} 这行配置也没人用，脚手架在、调用没写。
 * 于是支付单的金额直接取自请求体——实测能把 198 元的订单建成 0.01 元的支付单，
 * 也能给根本不存在的订单建单，全由调用方说了算。
 * <p>
 * 透传 {@code X-User-Id} 而不是另加一套鉴权：order-service 的 {@code GET /orders/{id}}
 * 本来就按这个头做归属过滤，拿别人的订单号查不到——「订单不存在」与「不属于你」在那里
 * 已经合并成同一个结果，正好避免支付接口变成订单号存在性的探测器。
 */
@FeignClient(name = "order-service", url = "${services.order-service-url:http://127.0.0.1:9003}")
public interface OrderClient {

    @GetMapping("/orders/{id}")
    Result<OrderSnapshot> getOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                   @PathVariable("id") Long id);
}
