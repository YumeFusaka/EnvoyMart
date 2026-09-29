package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

@FeignClient(name = "order-service", url = "${services.order-service-url:http://127.0.0.1:9003}")
public interface OrderClient {

    @GetMapping("/orders/{id}")
    Result<OrderResponse> getOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                   @PathVariable("id") Long id);

    @GetMapping("/orders/{id}/logistics")
    Result<LogisticsResponse> getLogistics(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                           @PathVariable("id") Long id);

    @PostMapping("/orders/{id}/cancel")
    Result<OrderResponse> cancelOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                      @PathVariable("id") Long id);
}
