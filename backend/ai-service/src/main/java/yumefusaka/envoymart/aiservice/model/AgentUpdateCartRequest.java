package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/** Agent 修改购物车行的最小请求契约。 */
@Data
public class AgentUpdateCartRequest {
    private Integer quantity;
}
