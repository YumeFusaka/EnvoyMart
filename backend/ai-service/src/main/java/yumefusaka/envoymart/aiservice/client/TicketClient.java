package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.aiservice.model.AgentCreateTicketRequest;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

import java.util.Map;

@FeignClient(name = "order-service", contextId = "ticketClient",
        url = "${services.order-service-url:http://127.0.0.1:9003}")
public interface TicketClient {
    @PostMapping("/tickets")
    org.springframework.http.ResponseEntity<Result<Map<String, Object>>> createReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestHeader("X-Operation-Id") String operationId, @RequestBody AgentCreateTicketRequest request);

    @GetMapping("/orders/internal/operation-receipts/{operationId}")
    org.springframework.http.ResponseEntity<Result<Object>> operationReceipt(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("operationId") String operationId,
            @org.springframework.web.bind.annotation.RequestParam("toolName") String toolName,
            @org.springframework.web.bind.annotation.RequestParam("parametersHash") String parametersHash);

    @GetMapping("/tickets/{id}")
    Result<Map<String, Object>> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long ticketId);
}
