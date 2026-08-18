package yumefusaka.envoymart.agent.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册中心 —— 路由用户意图到对应的 Skill。
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    public void registerAll(List<Skill> skillList) {
        skillList.forEach(this::register);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 根据用户消息路由到最佳匹配的 Skill。
     */
    public Optional<Skill> route(String userMessage) {
        return skills.values().stream()
                .filter(skill -> userMessage.contains(skill.getName()))
                .findFirst();
    }

    public List<String> listSkillNames() {
        return skills.values().stream().map(Skill::getName).toList();
    }
}
