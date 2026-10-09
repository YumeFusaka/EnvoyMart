package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.aiservice.model.AgentAddCartRequest;
import yumefusaka.envoymart.aiservice.model.AgentUpdateCartRequest;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleRequest;
import yumefusaka.envoymart.aiservice.model.AgentCheckoutRequest;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleResult;
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

    @PostMapping("/orders/by-no/{orderNo}/cancel")
    Result<OrderResponse> cancelOrderByNo(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                          @PathVariable("orderNo") String orderNo);

    /**
     * 售后资格预览 —— 判定在 order-service 的政策引擎里，这里只取结论。
     * <p>
     * 为什么 ai-service 不自己判：退货期限、类目政策、可退比例都是随业务变的规则，
     * 而「用户先在页面看到一套说法、再问 Agent 得到另一套」是这类功能最伤人的失败模式。
     * 结论只有一个来源，Agent 只负责把它说成人话。
     */
    // ==================== 购物车 ====================

    @GetMapping("/cart")
    Result<java.util.List<AgentCartItem>> listCart(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);

    @PostMapping("/cart/items")
    Result<AgentCartItem> addCartItem(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                              @RequestBody AgentAddCartRequest request);

    @PutMapping("/cart/items/{id}")
    Result<AgentCartItem> updateCartItem(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                         @PathVariable("id") Long id,
                                         @RequestBody AgentUpdateCartRequest request);

    @DeleteMapping("/cart/items/{id}")
    Result<Void> removeCartItem(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                @PathVariable("id") Long id);

    /**
     * 结算预览 —— 与 checkout 共用同一段装配代码，只算不改。
     * <p>
     * Agent 在下单前先问它「这单多少钱、有哪些券」，把金额告诉用户再确认，
     * 而不是直接下单。调用方拿到的是服务的真实计算，模型没有插话的余地。
     */
    @PostMapping("/orders/preview")
    Result<java.util.Map<String, Object>> previewOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);

    @PostMapping("/orders/checkout")
    Result<OrderResponse> checkout(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                           @RequestBody AgentCheckoutRequest request);

    // ==================== 售后 ====================

    @PostMapping("/after-sales")
    Result<AgentAfterSaleResult> applyAfterSale(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                        @RequestBody AgentAfterSaleRequest request);

    @GetMapping("/after-sales/preview")
    Result<AfterSalePreview> previewAfterSale(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                              @RequestParam("orderItemId") Long orderItemId,
                                              @RequestParam("type") String type,
                                              @RequestParam("qualityIssue") boolean qualityIssue);
}
