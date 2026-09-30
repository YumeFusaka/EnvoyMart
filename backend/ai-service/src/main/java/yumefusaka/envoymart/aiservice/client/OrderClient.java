package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.contract.AfterSalePreview;
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

    /**
     * 售后资格预览 —— 判定在 order-service 的政策引擎里，这里只取结论。
     * <p>
     * 为什么 ai-service 不自己判：退货期限、类目政策、可退比例都是随业务变的规则，
     * 而「用户先在页面看到一套说法、再问 Agent 得到另一套」是这类功能最伤人的失败模式。
     * 结论只有一个来源，Agent 只负责把它说成人话。
     */
    @GetMapping("/after-sales/preview")
    Result<AfterSalePreview> previewAfterSale(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                              @RequestParam("orderItemId") Long orderItemId,
                                              @RequestParam("type") String type,
                                              @RequestParam("qualityIssue") boolean qualityIssue);
}
