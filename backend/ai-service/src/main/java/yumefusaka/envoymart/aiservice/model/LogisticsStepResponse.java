package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LogisticsStepResponse {

    private String status;
    private String detail;
    private LocalDateTime time;
}
