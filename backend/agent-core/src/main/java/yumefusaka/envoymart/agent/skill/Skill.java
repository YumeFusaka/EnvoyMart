package yumefusaka.envoymart.agent.skill;

/**
 * Skill —— 领域能力的原子封装。
 * <p>
 * 每个 Skill 代表一个可独立执行的领域能力（如 "商品推荐"、"售后咨询"），
 * 拥有自己的上下文、工具和知识边界。
 */
public interface Skill {

    String getName();

    String getDescription();

    SkillResult execute(SkillContext context);
}
