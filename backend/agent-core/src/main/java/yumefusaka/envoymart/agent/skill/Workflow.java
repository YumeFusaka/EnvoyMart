package yumefusaka.envoymart.agent.skill;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流定义 —— 一组有序步骤，支持条件分支和并行。
 */
@Data
@Builder
public class Workflow {
    private String name;
    private String description;
    private List<WorkflowStep> steps;
}
