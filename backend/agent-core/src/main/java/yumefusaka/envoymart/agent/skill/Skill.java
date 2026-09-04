package yumefusaka.envoymart.agent.skill;

/**
 * Skill —— 领域能力的原子封装。
 * <p>
 * 每个 Skill 代表一条**确定性的业务流程**（如"售后引导"），
 * 与 ReAct / PAE 的区别在于：路径是预先设计好的，不交给模型即兴发挥。
 * 适合规则明确、步骤固定、不能出错的场景。
 */
public interface Skill {

    String getName();

    String getDescription();

    /**
     * 判断这条消息是否该由本 Skill 处理。
     * <p>
     * 默认按名称做字面匹配；实际 Skill 应重写为更稳的规则
     * （关键词 + 必要条件，例如"提到退货 **且** 给出了订单号"）。
     */
    default boolean matches(String userMessage) {
        return userMessage != null && userMessage.contains(getName());
    }

    SkillResult execute(SkillContext context);
}
