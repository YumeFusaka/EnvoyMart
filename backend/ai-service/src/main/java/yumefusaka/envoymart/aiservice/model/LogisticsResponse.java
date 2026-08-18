package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

import java.util.List;

@Data
public class LogisticsResponse {

    private Long orderId;
    private String orderNo;
    private String carrier;
    private String trackingNo;
    private List<LogisticsStepResponse> steps;
}
