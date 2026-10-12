package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.aiservice.model.AgentCouponResponse;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

import java.util.List;

@FeignClient(name = "promotion-service", url = "${services.promotion-service-url:http://127.0.0.1:9007}")
public interface PromotionClient {
    @PostMapping("/coupons/{id}/receive")
    org.springframework.http.ResponseEntity<Result<AgentCouponResponse>> receiveReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @PathVariable("id") Long couponId);

    @GetMapping("/coupons/internal/operation-receipts/{operationId}")
    org.springframework.http.ResponseEntity<Result<Object>> operationReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("operationId") String operationId,
            @RequestParam("toolName") String toolName, @RequestParam("parametersHash") String parametersHash);

    @GetMapping("/coupons/available")
    Result<List<AgentCouponResponse>> available(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);

    @GetMapping("/coupons/mine")
    Result<List<AgentCouponResponse>> mine(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam(value = "status", required = false) String status);
}
