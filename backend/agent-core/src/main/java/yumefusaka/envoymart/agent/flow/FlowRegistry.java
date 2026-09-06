package yumefusaka.envoymart.agent.flow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性流程注册中心。
 */
public class FlowRegistry {

    private final Map<String, DeterministicFlow> flows = new ConcurrentHashMap<>();

    public void register(DeterministicFlow flow) {
        flows.put(flow.getName(), flow);
    }

    public void registerAll(List<DeterministicFlow> flowList) {
        flowList.forEach(this::register);
    }

    public Optional<DeterministicFlow> get(String name) {
        return Optional.ofNullable(flows.get(name));
    }

    public List<DeterministicFlow> list() {
        return List.copyOf(flows.values());
    }

    /** 规则匹配 —— 模型不可用时的降级路径。 */
    public Optional<DeterministicFlow> routeByRule(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        return flows.values().stream()
                .filter(flow -> flow.matches(userMessage))
                .findFirst();
    }
}
