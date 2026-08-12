package yumefusaka.envoymart.aiservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.ContextManager;
import yumefusaka.envoymart.agent.core.PAEEngine;
import yumefusaka.envoymart.agent.core.ReActEngine;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.memory.LongTermMemory;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.memory.mem0.Mem0Client;
import yumefusaka.envoymart.agent.rag.*;
import yumefusaka.envoymart.agent.skill.SkillRegistry;
import yumefusaka.envoymart.agent.skill.WorkflowEngine;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.tool.LogisticsTool;
import yumefusaka.envoymart.aiservice.tool.OrderTool;
import yumefusaka.envoymart.aiservice.tool.ProductTool;

import java.util.List;

/**
 * Agent 框架的 Spring 配置 —— 将自研 agent-core 组件注入 Spring 容器。
 * <p>
 * 所有组件都可替换：切换 MockLLMProvider → OpenaiLLMProvider 即可接入真实模型。
 */
@Configuration
public class AiAgentConfig {

    @Bean
    public MockLLMProvider llmProvider() {
        return new MockLLMProvider();
    }

    @Bean
    public LLMConfig llmConfig() {
        return LLMConfig.builder()
                .model("mock")
                .temperature(0.7)
                .maxTokens(2048)
                .build();
    }

    @Bean
    public ToolRegistry toolRegistry(OrderClient orderClient, ProductClient productClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(List.of(
                new OrderTool(orderClient),
                new LogisticsTool(orderClient),
                new ProductTool(productClient)
        ));
        return registry;
    }

    @Bean
    public ShortTermMemory shortTermMemory() {
        return new ShortTermMemory(16);
    }

    @Bean
    public LongTermMemory longTermMemory() {
        return new LongTermMemory();
    }

    @Bean
    public Mem0Client mem0Client() {
        return new Mem0Client();
    }

    @Bean
    public OllamaEmbeddingService embeddingService() {
        // 优先使用本地 Ollama（nomic-embed-text），不可用时自动降级到 SimpleEmbeddingService
        return new OllamaEmbeddingService();
    }

    @Bean
    public InMemoryVectorStore vectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    public HybridRetriever retriever(VectorStore vectorStore, EmbeddingService embeddingService) {
        // 初始化领域知识文档
        List<Document> docs = List.of(
                Document.builder().id("promo_1").title("平台满减规则")
                        .content("本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。")
                        .tags(List.of("活动", "满减", "优惠")).scope("promotion").build(),
                Document.builder().id("after_sale_1").title("七天无理由与售后规则")
                        .content("除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。")
                        .tags(List.of("退货", "售后", "退款")).scope("after_sale").build(),
                Document.builder().id("logistics_1").title("物流说明")
                        .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。")
                        .tags(List.of("物流", "快递", "配送")).scope("logistics").build(),
                Document.builder().id("guide_1").title("百元耳机选购建议")
                        .content("学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。")
                        .tags(List.of("耳机", "学生党", "推荐")).scope("product_guide").build()
        );
        return new HybridRetriever(vectorStore, embeddingService, docs);
    }

    @Bean
    public SimpleRAGEngine ragEngine(EmbeddingService embeddingService,
                                     VectorStore vectorStore,
                                     Retriever retriever) {
        return new SimpleRAGEngine(embeddingService, vectorStore, retriever, 256, 32);
    }

    @Bean
    public ContextManager contextManager() {
        return new ContextManager(ContextManager.Config.builder()
                .maxRounds(10).maxTokens(4096).build());
    }

    @Bean
    public ReActEngine reActEngine(MockLLMProvider llmProvider, LLMConfig llmConfig, ToolRegistry toolRegistry) {
        return new ReActEngine(llmProvider, llmConfig, toolRegistry, 10);
    }

    @Bean
    public PAEEngine paeEngine(MockLLMProvider llmProvider, LLMConfig llmConfig, ToolRegistry toolRegistry) {
        return new PAEEngine(llmProvider, llmConfig, toolRegistry, 10);
    }

    @Bean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    public WorkflowEngine workflowEngine(SkillRegistry skillRegistry) {
        return new WorkflowEngine(skillRegistry);
    }

    @Bean
    public Agent agent(MockLLMProvider llmProvider,
                       ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry,
                       WorkflowEngine workflowEngine,
                       ShortTermMemory shortTermMemory,
                       LongTermMemory longTermMemory,
                       SimpleRAGEngine ragEngine,
                       ContextManager contextManager,
                       ReActEngine reActEngine,
                       PAEEngine paeEngine) {
        return new Agent(
                Agent.Config.builder().memoryWindow(16).ragTopK(3).build(),
                toolRegistry, skillRegistry, workflowEngine,
                shortTermMemory, longTermMemory, ragEngine,
                contextManager, reActEngine, paeEngine, llmProvider
        );
    }
}
