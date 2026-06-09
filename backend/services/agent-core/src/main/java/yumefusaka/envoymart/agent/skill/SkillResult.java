package yumefusaka.envoymart.agent.skill;

import lombok.Builder;
import lombok.Data;

/**
 * Skill 执行结果。
 */
@Data
@Builder
public class SkillResult {
    private boolean success;
    private String output;
    private Object data;
}
