package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanWithIntent;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphRetrievalTest {
    @Test
    void 模型误判无需检索也不能跳过显式知识或医疗问题() {
        for (String question : List.of("查询知识库是否存在政策甲的退货规则", "再查图谱鱼油与华法林的关系",
                "合并知识库和图谱依据回答鱼油与华法林能否同服，给出可追溯引用", "不存在成分甲和成分乙能一起吃吗", "合并上一轮结果带引用回答")) {
            assertThat(retrievalCount(question)).as(question).isEqualTo(1);
        }
    }

    @Test
    void 纯业务与闲聊仍按无需检索跳过() {
        for (String question : List.of("你好", "查询我的订单", "取消订单123", "退款进度到哪了")) {
            assertThat(retrievalCount(question)).as(question).isZero();
        }
    }

    private int retrievalCount(String question) {
        LLMProvider provider = new LLMProvider() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
                return LLMResponse.builder().content("本轮回答").build();
            }

            @Override
            public PlanWithIntent planWithIntent(String message, List<ToolDefinition> tools, String context) {
                return new PlanWithIntent(List.of(), false);
            }
        };
        AtomicInteger retrieved = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentGraph graph = new AgentGraph(provider, LLMConfig.builder().model("stub").build(), new ToolRegistry(), executor);
            graph.run("u", question, "", List.of(), null, null, null, () -> {
                retrieved.incrementAndGet();
                return AgentGraph.RetrievalResult.empty();
            });
        }
        return retrieved.get();
    }
}
