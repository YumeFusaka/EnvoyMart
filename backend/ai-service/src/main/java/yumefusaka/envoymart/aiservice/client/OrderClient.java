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
    @GetMapping("/orders/internal/operation-receipts/{operationId}")
    org.springframework.http.ResponseEntity<Result<Object>> operationReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("operationId") String operationId,
            @RequestParam("toolName") String toolName, @RequestParam("parametersHash") String parametersHash);

    @PostMapping("/cart/items")
    org.springframework.http.ResponseEntity<Result<AgentCartItem>> addCartItemReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @RequestBody AgentAddCartRequest request);

    @PutMapping("/cart/items/{id}")
    org.springframework.http.ResponseEntity<Result<AgentCartItem>> updateCartItemReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @PathVariable("id") Long id,
            @RequestBody AgentUpdateCartRequest request);

    @DeleteMapping("/cart/items/{id}")
    org.springframework.http.ResponseEntity<Result<Void>> removeCartItemReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @PathVariable("id") Long id);

    @PostMapping("/orders/checkout")
    org.springframework.http.ResponseEntity<Result<OrderResponse>> checkoutReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @RequestBody AgentCheckoutRequest request);

    @PostMapping("/orders/{id}/cancel")
    org.springframework.http.ResponseEntity<Result<OrderResponse>> cancelOrderReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @PathVariable("id") Long id);

    @PostMapping("/orders/by-no/{orderNo}/cancel")
    org.springframework.http.ResponseEntity<Result<OrderResponse>> cancelOrderByNoReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @PathVariable("orderNo") String orderNo);

    @PostMapping("/after-sales")
    org.springframework.http.ResponseEntity<Result<AgentAfterSaleResult>> applyAfterSaleReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @RequestBody AgentAfterSaleRequest request);

    @GetMapping("/orders/{id}")
    Result<OrderResponse> getOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                   @PathVariable("id") Long id);

    @GetMapping("/orders/{id}/logistics")
    Result<LogisticsResponse> getLogistics(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                           @PathVariable("id") Long id);

    // ==================== 购物车 ====================

    @GetMapping("/cart")
    Result<java.util.List<AgentCartItem>> listCart(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);

    /**
     * 结算预览 —— 与 checkout 共用同一段装配代码，只算不改。
     * <p>
     * Agent 在下单前先问它「这单多少钱、有哪些券」，把金额告诉用户再确认，
     * 而不是直接下单。调用方拿到的是服务的真实计算，模型没有插话的余地。
     */
    @PostMapping("/orders/preview")
    Result<java.util.Map<String, Object>> previewOrder(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);

    // ==================== 售后 ====================

    /** 售后流程与提交工具共用交易域资格预览；type 使用 REFUND_ONLY / RETURN_REFUND / EXCHANGE。 */
    @GetMapping("/after-sales/preview")
    Result<AfterSalePreview> previewAfterSale(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
                                              @RequestParam("orderItemId") Long orderItemId,
                                              @RequestParam("type") String type,
                                              @RequestParam("qualityIssue") boolean qualityIssue);
}
