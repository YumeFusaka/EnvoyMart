package yumefusaka.envoymart.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 物流轨迹。
 * <p>
 * <b>由 order-service 发出，AI 服务的物流工具消费。</b>
 * {@code steps} 尚未发货时是空列表而不是 null——消费方少一处判空，
 * 也就少一次「忘判空 → 工具抛异常 → 模型收到失败结果后开始编」。
 */
@Data
@Builder
public class LogisticsResponse {

    private Long orderId;
    private String orderNo;
    private String carrier;
    private String trackingNo;
    private List<LogisticsStepResponse> steps;
}
