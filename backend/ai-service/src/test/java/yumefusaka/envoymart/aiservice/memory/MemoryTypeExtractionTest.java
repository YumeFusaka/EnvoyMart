package yumefusaka.envoymart.aiservice.memory;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.MemoryItem;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽取出来的条目要带上真实的类型。
 * <p>
 * 类型不是给人看的标签，它是保留策略的依据：偏好长期有效、不参与淘汰，事件会过期、
 * 满了就该走。全部标成同一种，等于这个维度不存在——分层淘汰无从谈起。
 */
class MemoryTypeExtractionTest {

    private static LlmMemoryConsolidator consolidatorReturning(String json) {
        return new LlmMemoryConsolidator(
                new MockLLMProvider() {
                    @Override
                    public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                        return LLMResponse.builder().content(json).build();
                    }
                },
                LLMConfig.builder().model("stub").temperature(0.0).build());
    }

    private MemoryItem userMessage(String content) {
        return MemoryItem.builder()
                .id(UUID.randomUUID().toString()).userId("u1001")
                .content("user: " + content).type(MemoryItem.Type.MESSAGE).build();
    }

    @Test
    void 长期偏好标成PREFERENCE_发生过的事标成FACT() {
        String json = """
                {"profile":[],
                 "episodes":[{"content":"用户是学生党，预算有限","type":"PREFERENCE"},
                             {"content":"用户上次抱怨过物流慢","type":"FACT"}]}
                """;

        MemoryConsolidator.ConsolidationResult result =
                consolidatorReturning(json).extract("u1001", List.of(userMessage("随便聊聊")));

        assertThat(result.episodes()).extracting(item -> item.getType())
                .containsExactly(MemoryItem.Type.PREFERENCE, MemoryItem.Type.FACT);
    }

    /**
     * 类型无法识别时按事件处理。
     * <p>
     * 两边的错法代价不对称：误判成事件的偏好会被正常淘汰，误判成偏好的噪声则永久占着
     * 不参与淘汰的配额——那是不可回收的。所以未知一律往「可淘汰」一侧靠。
     */
    @Test
    void 类型无法识别时按事件处理() {
        String json = """
                {"profile":[],
                 "episodes":[{"content":"用户提过想买跑鞋","type":"WHATEVER"},
                             {"content":"用户没有给类型"}]}
                """;

        MemoryConsolidator.ConsolidationResult result =
                consolidatorReturning(json).extract("u1001", List.of(userMessage("随便聊聊")));

        assertThat(result.episodes()).extracting(item -> item.getType())
                .containsOnly(MemoryItem.Type.FACT);
    }
}
