package yumefusaka.envoymart.reviewservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.reviewservice.model.OrderSnapshot;

/**
 * 订单回查 —— 用来确认「这条评价确实来自一次真实购买」。
 * <p>
 * 透传 {@code X-User-Id} 而不是另加一套鉴权：order-service 的 {@code GET /orders/{id}}
 * 本来就按这个头做归属过滤，拿别人的订单号查不到，「订单不存在」与「不属于你」
 * 在那里已经合并成同一个结果，正好避免评价接口变成订单号存在性的探测器。
 */
@FeignClient(name = "order-service", url = "${services.order-service-url:http://127.0.0.1:9003}")
public interface OrderClient {

    @GetMapping("/orders/{id}")
    Result<OrderSnapshot> getOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                   @PathVariable("id") Long id);
}
