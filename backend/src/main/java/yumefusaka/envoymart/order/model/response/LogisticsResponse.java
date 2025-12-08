package yumefusaka.envoymart.order.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LogisticsResponse {

    private Long orderId;
    private String orderNo;
    private String carrier;
    private String trackingNo;
    private List<LogisticsStepResponse> steps;
}
