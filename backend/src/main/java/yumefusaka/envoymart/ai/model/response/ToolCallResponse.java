package yumefusaka.envoymart.ai.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolCallResponse {

    private String tool;
    private String input;
    private String output;
}
