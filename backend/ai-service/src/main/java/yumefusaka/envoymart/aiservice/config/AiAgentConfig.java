package yumefusaka.envoymart.aiservice.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.ContextManager;
import yumefusaka.envoymart.agent.core.PAEEngine;
import yumefusaka.envoymart.agent.core.ReActEngine;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
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
import yumefusaka.envoymart.aiservice.llm.SpringAiLLMProvider;
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

    /**
     * 配了模型 API Key 就走 Spring AI 接入层；没配则回退 Mock，
     * 保证本地无 Key 也能启动并跑通链路。
     */
    @Bean
    @ConditionalOnExpression("'${spring.ai.openai.api-key:}'.length() > 0")
    public LLMProvider springAiLLMProvider(ChatModel chatModel, ToolRegistry toolRegistry) {
        return new SpringAiLLMProvider(chatModel, toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(LLMProvider.class)
    public LLMProvider mockLLMProvider() {
        return new MockLLMProvider();
    }

    @Bean
    public LLMConfig llmConfig(@Value("${spring.ai.openai.chat.model:mock}") String model) {
        return LLMConfig.builder()
                .model(model)
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

    /**
     * 本地降级用的内存向量库。生产走 Milvus（见 MilvusVectorStoreConfig）。
     */
    @Bean
    public VectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    public HybridRetriever retriever(VectorStore vectorStore, EmbeddingService embeddingService) {
        return new HybridRetriever(vectorStore, embeddingService, knowledgeDocuments());
    }

    /**
     * 领域知识文档 —— 同时供 BM25 关键词检索与向量库索引使用。
     */
    private List<Document> knowledgeDocuments() {
        return List.of(
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
    }

    @Bean
    public SimpleRAGEngine ragEngine(EmbeddingService embeddingService,
                                     VectorStore vectorStore,
                                     Retriever retriever) {
        SimpleRAGEngine engine = new SimpleRAGEngine(embeddingService, vectorStore, retriever, 256, 32);
        // 启动时把领域知识灌入向量库；不调用 ingest 的话 ANN 检索永远返回空
        engine.ingestBatch(knowledgeDocuments());
        return engine;
    }

    @Bean
    public ContextManager contextManager() {
        return new ContextManager(ContextManager.Config.builder()
                .maxRounds(10).maxTokens(4096).build());
    }

    @Bean
    public ReActEngine reActEngine(LLMProvider llmProvider, LLMConfig llmConfig, ToolRegistry toolRegistry) {
        return new ReActEngine(llmProvider, llmConfig, toolRegistry, 10);
    }

    @Bean
    public PAEEngine paeEngine(LLMProvider llmProvider, LLMConfig llmConfig, ToolRegistry toolRegistry) {
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
    public Agent agent(LLMProvider llmProvider,
                       ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry,
                       WorkflowEngine workflowEngine,
                       ShortTermMemory shortTermMemory,
                       LongTermMemory longTermMemory,
                       SimpleRAGEngine ragEngine,
                       ContextManager contextManager,
                       ReActEngine reActEngine,
                       PAEEngine paeEngine,
                       LLMConfig llmConfig) {
        return new Agent(
                Agent.Config.builder().memoryWindow(16).ragTopK(3).build(),
                toolRegistry, skillRegistry, workflowEngine,
                shortTermMemory, longTermMemory, ragEngine,
                contextManager, reActEngine, paeEngine, llmProvider, llmConfig
        );
    }
}
