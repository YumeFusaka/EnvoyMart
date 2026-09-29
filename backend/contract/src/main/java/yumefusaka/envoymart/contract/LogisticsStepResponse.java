package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 物流轨迹的一步。{@code detail} 是给用户看的那句话，不是状态码 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogisticsStepResponse {

    private String status;
    private String detail;
    private LocalDateTime time;
}
