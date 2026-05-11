package yumefusaka.envoymart.order.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LogisticsStepResponse {

    private String status;
    private String detail;
    private LocalDateTime time;
}
