package yumefusaka.envoymart.agent.rag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用一次独立模型调用判断未带角标句子的语义状态。
 * 规则代码只负责发现候选和拦截硬冲突，避免把正常的承接、比较、单位说明误删。
 */
public final class SemanticGroundingVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM = """
            你是回答溯源审核员。请审核回答中的每一句候选内容，并结合给出的知识证据和业务工具事实判断。
            只输出 JSON 数组，不要 markdown，不要解释。每项格式：
            {"index":1,"decision":"SUPPORTED|CONVERSATIONAL|UNSUPPORTED|CONFLICTED"}

            判定规则：
            - SUPPORTED：证据或工具事实直接支持，即使句子没有角标也保留。
            - CONVERSATIONAL：正常承接、总结、比较、单位换算或解释上下文，不需要独立引用，保留。
            - UNSUPPORTED：是具体事实或建议，但给出的证据和工具事实都无法支持。
            - CONFLICTED：与工具事实明确矛盾；不要把它判成 UNSUPPORTED。
            不要因为句子含数字就判无依据；要理解同一商品上下文、同义表达和单位换算。
            """;

    private SemanticGroundingVerifier() {
    }

    public record Verdict(List<String> unsupported, boolean evaluated) {
        public static Verdict unavailable() {
            return new Verdict(List.of(), false);
        }
    }

    public static Verdict verify(LLMProvider llm, LLMConfig config, String userMessage,
                                 String reply, List<String> candidates,
                                 List<DocumentChunk> evidence, String toolFacts) {
        if (llm == null || !llm.supportsReasoning() || candidates == null || candidates.isEmpty()) {
            return Verdict.unavailable();
        }
        StringBuilder prompt = new StringBuilder("用户问题：").append(userMessage).append("\n\n")
                .append("知识证据：\n");
        for (int i = 0; i < evidence.size(); i++) {
            prompt.append("[证据 ").append(i + 1).append("] ").append(evidence.get(i).getContent()).append('\n');
        }
        prompt.append("\n业务工具事实：\n").append(toolFacts == null ? "无" : toolFacts)
                .append("\n\n完整回答：\n").append(reply).append("\n\n候选句：\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append(i + 1).append(". ").append(candidates.get(i)).append('\n');
        }
        try {
            var response = llm.chat(List.of(
                    ChatMessage.system(SYSTEM), ChatMessage.user(prompt.toString())), config);
            String json = response == null ? null : response.getContent();
            if (json == null || json.isBlank()) {
                return Verdict.unavailable();
            }
            json = json.replace("```json", "").replace("```", "").trim();
            List<Map<String, Object>> rows = MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() { });
            List<String> unsupported = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                int index = Integer.parseInt(String.valueOf(row.getOrDefault("index", "0"))) - 1;
                String decision = String.valueOf(row.getOrDefault("decision", ""));
                if (index >= 0 && index < candidates.size() && "UNSUPPORTED".equals(decision)) {
                    unsupported.add(candidates.get(index));
                }
            }
            return new Verdict(List.copyOf(unsupported), true);
        } catch (Exception ignored) {
            return Verdict.unavailable();
        }
    }
}
