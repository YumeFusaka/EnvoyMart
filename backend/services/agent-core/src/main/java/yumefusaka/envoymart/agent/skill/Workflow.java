package yumefusaka.envoymart.agent.skill;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流定义 —— 一组有序步骤，支持条件分支和并行。
 * ponytail: 当前为线性步骤列表，生产环境应支持 DAG 拓扑。
 */
@Data
@Builder
public class Workflow {
    private String name;
    private String description;
    private List<WorkflowStep> steps;
}
