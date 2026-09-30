package yumefusaka.envoymart.orderservice.model;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重开已解决的工单。
 * <p>
 * 说明可选：重开这个动作本身已经说清楚「上次的解决不算数」，
 * 追问一句「哪个问题还在」是礼貌，不是必填。
 */
@Data
public class TicketReopenRequest {

    @Size(max = 2000, message = "内容最长 2000 位")
    private String content;
}
