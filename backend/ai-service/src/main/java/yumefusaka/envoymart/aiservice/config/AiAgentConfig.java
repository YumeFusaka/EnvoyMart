package yumefusaka.envoymart.aiservice.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.core.AgentGraph;
import yumefusaka.envoymart.agent.flow.FlowRegistry;
import yumefusaka.envoymart.agent.flow.IntentRouter;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.MockLLMProvider;
import yumefusaka.envoymart.agent.memory.LongTermMemory;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.rag.*;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.memory.LlmMemoryConsolidator;
import yumefusaka.envoymart.aiservice.flow.AfterSaleFlow;
import yumefusaka.envoymart.aiservice.rag.DashScopeReranker;
import yumefusaka.envoymart.aiservice.rag.MilvusVectorStore;
import yumefusaka.envoymart.aiservice.rag.SpringAiEmbeddingService;
import yumefusaka.envoymart.aiservice.llm.SpringAiLLMProvider;
import yumefusaka.envoymart.aiservice.tool.CancelOrderTool;
import yumefusaka.envoymart.aiservice.tool.LogisticsTool;
import yumefusaka.envoymart.aiservice.tool.OrderTool;
import yumefusaka.envoymart.aiservice.tool.ProductTool;
import yumefusaka.envoymart.aiservice.tool.ToolRegistryCallbackProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    public LLMProvider springAiLLMProvider(ChatModel chatModel, ToolRegistry toolRegistry, LLMConfig llmConfig) {
        return new SpringAiLLMProvider(chatModel, toolRegistry, llmConfig);
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
                new ProductTool(productClient),
                new CancelOrderTool(orderClient)
        ));
        return registry;
    }

    /**
     * 把 ToolRegistry 的工具发布给 Spring AI，MCP Server 会自动注册为 MCP 工具。
     * 同一份工具定义既供 Agent 调用，也供外部 MCP 客户端调用。
     */
    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(ToolRegistry toolRegistry) {
        return new ToolRegistryCallbackProvider(toolRegistry);
    }

    @Bean
    public ShortTermMemory shortTermMemory() {
        return new ShortTermMemory(16);
    }

    @Bean
    public LongTermMemory longTermMemory(@Qualifier("memoryVectorStore") VectorStore memoryVectorStore) {
        return new LongTermMemory(memoryVectorStore);
    }

    @Bean
    public MemoryConsolidator memoryConsolidator(LLMProvider llmProvider, LLMConfig llmConfig) {
        return new LlmMemoryConsolidator(llmProvider, llmConfig);
    }

    /** 配了模型 Key 就用 Spring AI 的 EmbeddingModel（语义召回才有意义）。 */
    @Bean
    @ConditionalOnExpression("'${spring.ai.openai.api-key:}'.length() > 0")
    public EmbeddingService springAiEmbeddingService(org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        return new SpringAiEmbeddingService(embeddingModel);
    }

    /** 无 Key 时退回本地 Ollama（nomic-embed-text），不可用再降级到哈希向量。 */
    @Bean
    @ConditionalOnMissingBean(EmbeddingService.class)
    public EmbeddingService ollamaEmbeddingService() {
        return new OllamaEmbeddingService();
    }

    /** 知识库：本地降级用内存向量库（milvus profile 下不启用）。 */
    @Bean("knowledgeVectorStore")
    @Primary
    @Profile("!milvus")
    public VectorStore inMemoryKnowledgeVectorStore(EmbeddingService embeddingService) {
        return new InMemoryVectorStore(embeddingService);
    }

    /** 知识库：生产用 Milvus，向量化由 Spring AI 的 EmbeddingModel 完成。 */
    @Bean("knowledgeVectorStore")
    @Primary
    @Profile("milvus")
    public VectorStore milvusKnowledgeVectorStore(org.springframework.ai.vectorstore.VectorStore delegate) {
        return new MilvusVectorStore(delegate);
    }

    /**
     * 长期记忆专用向量库 —— 与知识库隔离，避免记忆条目污染知识检索结果。
     * 本地用独立的内存实例，生产用独立 collection。
     */
    @Bean("memoryVectorStore")
    @Profile("!milvus")
    public VectorStore inMemoryMemoryVectorStore(EmbeddingService embeddingService) {
        return new InMemoryVectorStore(embeddingService);
    }

    @Bean("memoryVectorStore")
    @Profile("milvus")
    public VectorStore milvusMemoryVectorStore(io.milvus.client.MilvusServiceClient milvusClient,
                                               org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        org.springframework.ai.vectorstore.milvus.MilvusVectorStore delegate =
                org.springframework.ai.vectorstore.milvus.MilvusVectorStore
                        .builder(milvusClient, embeddingModel)
                        .collectionName("envoymart_memory")
                        .embeddingDimension(embeddingModel.dimensions())
                        .initializeSchema(true)
                        .build();
        try {
            // 手工构造的实例不走 Spring 生命周期，需显式触发建表
            delegate.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Milvus 记忆库失败", e);
        }
        return new MilvusVectorStore(delegate);
    }

    /** 配了 Key 就用百炼 gte-rerank 做 cross-encoder 精排。 */
    @Bean
    @ConditionalOnExpression("'${spring.ai.openai.api-key:}'.length() > 0")
    public Reranker dashScopeReranker(@Value("${spring.ai.openai.api-key}") String apiKey,
                                      @Value("${envoymart.rerank.model:gte-rerank-v2}") String model,
                                      @Value("${envoymart.rerank.endpoint:}") String endpoint,
                                      @Value("${envoymart.rerank.timeout-ms:5000}") long timeoutMs) {
        return new DashScopeReranker(apiKey, model, endpoint, java.time.Duration.ofMillis(timeoutMs));
    }

    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    public Reranker noopReranker() {
        return Reranker.NOOP;
    }

    @Bean
    public HybridRetriever retriever(@Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                                     Reranker reranker) {
        return new HybridRetriever(vectorStore, knowledgeDocuments(), reranker);
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
    public SimpleRAGEngine ragEngine(VectorStore vectorStore, Retriever retriever) {
        SimpleRAGEngine engine = new SimpleRAGEngine(vectorStore, retriever, 256, 32);
        // 启动时把领域知识灌入向量库；不调用 ingest 的话 ANN 检索永远返回空
        engine.ingestBatch(knowledgeDocuments());
        return engine;
    }

    /**
     * 确定性流程注册 —— 业务判定由代码完成，不交给模型自由发挥。
     */
    @Bean
    public FlowRegistry flowRegistry() {
        FlowRegistry registry = new FlowRegistry();
        registry.registerAll(List.of(new AfterSaleFlow()));
        return registry;
    }

    /**
     * 入口守卫：判断一条消息该走确定性流程，还是交给执行图。
     * 模型负责语义判断，规则负责参数齐备性校验；模型不可用时纯走规则。
     */
    @Bean
    public IntentRouter intentRouter(LLMProvider llmProvider, LLMConfig llmConfig, FlowRegistry flowRegistry) {
        return new IntentRouter(llmProvider, llmConfig, flowRegistry);
    }

    /**
     * 执行计划中无依赖步骤的并发执行器。
     * 任务是阻塞式外部调用，用虚拟线程比固定线程池更合适。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public AgentGraph agentGraph(LLMProvider llmProvider, LLMConfig llmConfig,
                                 ToolRegistry toolRegistry, ExecutorService agentExecutor) {
        return new AgentGraph(llmProvider, llmConfig, toolRegistry, agentExecutor);
    }

    @Bean
    public Agent agent(ToolRegistry toolRegistry,
                       IntentRouter intentRouter,
                       AgentGraph agentGraph,
                       ShortTermMemory shortTermMemory,
                       LongTermMemory longTermMemory,
                       SimpleRAGEngine ragEngine,
                       MemoryConsolidator memoryConsolidator) {
        return new Agent(
                Agent.Config.builder().memoryWindow(16).ragTopK(3).longTermRecallTopK(3).build(),
                toolRegistry, intentRouter, agentGraph,
                shortTermMemory, longTermMemory, ragEngine,
                memoryConsolidator
        );
    }
}
