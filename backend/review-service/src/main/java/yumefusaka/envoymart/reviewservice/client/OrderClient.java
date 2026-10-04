package yumefusaka.envoymart.reviewservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;

import java.time.LocalDateTime;

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
    Result<OrderResponse> getOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                   @PathVariable("id") Long id);

    /**
     * 该用户最早一次已收货订单的时间，给「新账号刷评」判据用。
     * <p>
     * <b>这条走内部通道，不带身份头</b>：网关对 {@code /orders/internal/} 一律 404，
     * 只有 Feign 直连可达。它读的是别人的交易时间线，带身份头反而会让订单服务
     * 按归属过滤 —— 而这里问的正是「这个 userId 的第一次收货是什么时候」，
     * 归属在评价侧已经由 {@code requirePurchased} 校验过了。
     * <p>
     * 返回 null 表示从未有过收货。
     */
    @GetMapping("/orders/internal/users/{userId}/first-received")
    Result<LocalDateTime> firstReceivedAt(@PathVariable("userId") String userId);
}
