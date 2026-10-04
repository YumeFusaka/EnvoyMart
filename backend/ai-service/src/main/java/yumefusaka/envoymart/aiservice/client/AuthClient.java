package yumefusaka.envoymart.aiservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import yumefusaka.envoymart.aiservice.model.AgentAddress;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

import java.util.List;

@FeignClient(name = "auth-service", url = "${services.auth-service-url:http://127.0.0.1:9001}")
public interface AuthClient {

    /**
     * 当前用户的收货地址簿。
     * <p>
     * 地址归属由 auth-service 从 {@code X-User-Id} 判定，本接口不接受 userId 参数——
     * 传谁的 id 就能读谁的地址，这种接口不该存在。这里带的 header 与其它
     * 服务间调用同源，都是网关口径的身份头。
     */
    @GetMapping("/auth/addresses")
    Result<List<AgentAddress>> listAddresses(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId);
}
