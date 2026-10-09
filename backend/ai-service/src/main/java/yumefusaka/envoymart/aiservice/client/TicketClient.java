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
    Result<Map<String, Object>> create(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestBody AgentCreateTicketRequest request);

    @GetMapping("/tickets/{id}")
    Result<Map<String, Object>> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long ticketId);
}
