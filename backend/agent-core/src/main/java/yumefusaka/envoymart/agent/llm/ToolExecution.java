package yumefusaka.envoymart.agent.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 一次工具调用的执行记录 —— 供前端展示调用轨迹与排障。
 */
@Data
@Builder
public class ToolExecution {

    /** 工具名 */
    private String tool;
    /** 调用入参（JSON 字符串） */
    private String input;
    /** 工具返回的文本结果 */
    private String output;
    private boolean success;
    /** 结构化结果，便于上层做二次加工（如抽取推荐商品） */
    private Object rawData;
}
