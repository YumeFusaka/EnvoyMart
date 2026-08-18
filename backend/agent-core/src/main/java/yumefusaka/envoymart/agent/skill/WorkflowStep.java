package yumefusaka.envoymart.agent.skill;

import lombok.Builder;
import lombok.Data;

/**
 * 工作流中的一个步骤 —— 可以是一个 Skill 调用或子工作流。
 */
@Data
@Builder
public class WorkflowStep {
    private String name;
    private String skillName;
    private String inputMapping;     // 从上级上下文提取入参的 SpEL / JSONPath
    private boolean optional;        // true = 失败不中断流程
}
