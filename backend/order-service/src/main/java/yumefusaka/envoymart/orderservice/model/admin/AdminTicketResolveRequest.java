package yumefusaka.envoymart.orderservice.model.admin;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标记解决，可附一段解决说明。
 * <p>
 * 说明可选：客服常常在对话里已经说清楚了，只差把状态推到「已解决」；
 * 强制再写一段只会逼出"已处理"三个字。填了就作为一条客服消息落进消息流。
 */
@Data
public class AdminTicketResolveRequest {

    @Size(max = 2000, message = "说明最长 2000 位")
    private String content;
}
