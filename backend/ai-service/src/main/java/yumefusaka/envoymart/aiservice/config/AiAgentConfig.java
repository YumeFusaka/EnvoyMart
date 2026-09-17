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
import yumefusaka.envoymart.agent.memory.EpisodicMemory;
import yumefusaka.envoymart.agent.memory.UserProfileStore;
import yumefusaka.envoymart.agent.memory.MemoryConsolidator;
import yumefusaka.envoymart.agent.memory.ProfileRepository;
import yumefusaka.envoymart.agent.memory.ShortTermMemory;
import yumefusaka.envoymart.agent.rag.*;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.memory.LlmMemoryConsolidator;
import yumefusaka.envoymart.aiservice.flow.AfterSaleFlow;
import yumefusaka.envoymart.aiservice.rag.MilvusVectorStore;
import yumefusaka.envoymart.aiservice.rag.SpringAiEmbeddingService;
import yumefusaka.envoymart.aiservice.llm.SpringAiLLMProvider;
import yumefusaka.envoymart.aiservice.tool.CancelOrderTool;
import yumefusaka.envoymart.aiservice.tool.LogisticsTool;
import yumefusaka.envoymart.aiservice.tool.OrderTool;
import yumefusaka.envoymart.aiservice.tool.ProductTool;
import io.micrometer.core.instrument.MeterRegistry;
import yumefusaka.envoymart.aiservice.tool.MicrometerToolCallListener;
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
    @ConditionalOnExpression("'${spring.ai.openai.chat.api-key:}'.length() > 0")
    public LLMProvider springAiLLMProvider(ChatModel chatModel, ToolRegistry toolRegistry, LLMConfig llmConfig,
                                           MeterRegistry meterRegistry) {
        return new SpringAiLLMProvider(chatModel, toolRegistry, llmConfig, meterRegistry);
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

    /**
     * 工具注册表 —— 观测点挂在这一层。
     * <p>
     * 计划节点、ReAct 循环、MCP 三条来路的工具调用最终都汇到 {@code ToolRegistry.execute}，
     * 埋点放这里才能一次覆盖全部；放在某一个 ToolCallback 实现里会漏掉不经过它的路径。
     */
    @Bean
    public ToolRegistry toolRegistry(OrderClient orderClient, ProductClient productClient,
                                     MeterRegistry meterRegistry) {
        ToolRegistry registry = new ToolRegistry(new MicrometerToolCallListener(meterRegistry));
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

    /**
     * 情节记忆 —— 用户经历过的事件，按 userId 隔离、按需语义召回。
     * <p>
     * 与画像分开：画像结构化且全量注入，情节自由文本且需要检索。
     */
    @Bean
    public EpisodicMemory episodicMemory(@Qualifier("memoryVectorStore") VectorStore memoryVectorStore) {
        return new EpisodicMemory(memoryVectorStore);
    }

    /**
     * 用户画像存储 —— 固定槽位、覆盖式更新，按 userId 隔离。
     * <p>
     * 挂上 {@link ProfileRepository}（Redis 实现）后重启不丢，与写在向量库里的情节记忆对称。
     * 仓库不可用时 {@code UserProfileStore} 内部会兜住并降级为纯内存，不影响对话。
     */
    @Bean
    public UserProfileStore userProfileStore(ProfileRepository profileRepository) {
        return new UserProfileStore(profileRepository);
    }

    @Bean
    public MemoryConsolidator memoryConsolidator(LLMProvider llmProvider, LLMConfig llmConfig) {
        return new LlmMemoryConsolidator(llmProvider, llmConfig);
    }

    /** 配了模型 Key 就用 Spring AI 的 EmbeddingModel（语义召回才有意义）。 */
    @Bean
    @ConditionalOnExpression("'${spring.ai.openai.embedding.api-key:}'.length() > 0")
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

    /** 配了重排 Key 就用百炼 gte-rerank 做 cross-encoder 精排（默认复用 embedding 的 Key）。 */
    @Bean
    @ConditionalOnExpression("'${envoymart.rerank.api-key:}'.length() > 0")
    public Reranker dashScopeReranker(@Value("${envoymart.rerank.api-key}") String apiKey,
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
     * <p>
     * <b>语料规模直接影响检索指标的解读</b>：文档数越少、取 top-K 的随机命中率越高。
     * 早先只有 4 篇，取 top-3 的随机基线就有 0.75，任何检索器都能轻松达标，
     * 指标失去了区分度。这里扩到十几篇，覆盖同一批业务域下的多个细分主题——
     * 也正是真实知识库的样子：一个主题下有多篇文档相互竞争，而不是一问对一答。
     * <p>
     * 这套语料与 {@code RetrievalFixtures} 的评测语料是<b>两套独立数据</b>，
     * 规模与主题分布接近，但内容不重合，指标不构成对彼此的复现。
     */
    private List<Document> knowledgeDocuments() {
        return List.of(
                // —— 活动与优惠 ——
                Document.builder().id("promo_1").title("平台满减规则")
                        .content("本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。")
                        .tags(List.of("活动", "满减", "优惠")).scope("promotion").build(),
                Document.builder().id("promo_2").title("优惠券使用限制")
                        .content("店铺券与平台券可以叠加，但同类券之间互斥；已使用的优惠券在订单取消后 24 小时内退还。")
                        .tags(List.of("优惠券", "叠加", "退还")).scope("promotion").build(),
                Document.builder().id("promo_3").title("会员等级与权益")
                        .content("会员分普通、银卡、金卡三档，累计消费满 2000 元升银卡享 98 折，满 8000 元升金卡享 95 折与专属客服。")
                        .tags(List.of("会员", "等级", "折扣")).scope("promotion").build(),
                Document.builder().id("promo_4").title("秒杀活动规则")
                        .content("秒杀商品每场限购一件，下单后 15 分钟内未支付自动释放库存，不参与其他优惠叠加。")
                        .tags(List.of("秒杀", "限购", "库存")).scope("promotion").build(),

                // —— 售后 ——
                Document.builder().id("after_sale_1").title("七天无理由与售后规则")
                        .content("除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。")
                        .tags(List.of("退货", "售后", "退款")).scope("after_sale").build(),
                Document.builder().id("after_sale_2").title("退货运费承担规则")
                        .content("无理由退货由买家承担运费；商品本身存在质量问题或发错货的，运费由平台承担并补贴 12 元。")
                        .tags(List.of("运费", "退货", "补贴")).scope("after_sale").build(),
                Document.builder().id("after_sale_3").title("换货流程与时效")
                        .content("换货需先提交申请，审核通过后寄回原商品，平台签收确认后 48 小时内发出新商品。")
                        .tags(List.of("换货", "流程", "时效")).scope("after_sale").build(),
                Document.builder().id("after_sale_4").title("价保规则")
                        .content("自营商品支持 15 天价保，下单后同款商品降价可申请补差价，需提供降价截图且商品未拆封。")
                        .tags(List.of("价保", "补差价", "降价")).scope("after_sale").build(),

                // —— 物流 ——
                Document.builder().id("logistics_1").title("物流说明")
                        .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达。")
                        .tags(List.of("物流", "快递", "配送")).scope("logistics").build(),
                Document.builder().id("logistics_2").title("偏远地区配送范围")
                        .content("新疆、西藏、内蒙古部分地区暂不支持次日达，配送时效为 5 到 8 天，部分大件商品无法送达。")
                        .tags(List.of("偏远地区", "配送", "时效")).scope("logistics").build(),
                Document.builder().id("logistics_3").title("签收与验货须知")
                        .content("贵重商品建议当面验货后再签收；发现外包装破损可拒收并联系客服，拒收不产生额外费用。")
                        .tags(List.of("签收", "验货", "拒收")).scope("logistics").build(),

                // —— 支付与发票 ——
                Document.builder().id("payment_1").title("支持的支付方式")
                        .content("支持微信、支付宝、银联卡与平台余额支付；余额支付可享 99 折，单笔上限 5000 元。")
                        .tags(List.of("支付", "方式", "余额")).scope("payment").build(),
                Document.builder().id("invoice_1").title("发票开具与类型")
                        .content("下单时可申请电子普通发票，确认收货后可补开；增值税专用发票需提供企业资质，3 个工作日开出。")
                        .tags(List.of("发票", "开票", "增值税")).scope("payment").build(),

                // —— 选购建议 ——
                Document.builder().id("guide_1").title("百元耳机选购建议")
                        .content("学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。")
                        .tags(List.of("耳机", "学生党", "推荐")).scope("product_guide").build(),
                Document.builder().id("guide_2").title("笔记本选购要点")
                        .content("日常办公优先看重量与续航，16GB 内存起步；涉及视频剪辑或建模需独显，散热规格比纸面参数更重要。")
                        .tags(List.of("笔记本", "选购", "配置")).scope("product_guide").build()
        );
    }

    @Bean
    public SimpleRAGEngine ragEngine(@Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                                     Retriever retriever) {
        SimpleRAGEngine engine = new SimpleRAGEngine(vectorStore, retriever, 256, 32);

        // 启动时把领域知识灌入向量库；不调用 ingest 的话 ANN 检索永远返回空。
        //
        // 必须先按 docId 清掉旧切片再写入——**入库没有幂等性，而持久化向量库会跨重启累积**：
        // 实测接上 Milvus 后连续启动，集合里堆到了 38 条而实际只有 15 篇文档，
        // 重复条目会挤占 topK、让同一篇文档在结果里出现多次。
        // 内存向量库每次启动都是空的，所以这个缺陷在本地降级路径下永远不会暴露。
        List<Document> documents = knowledgeDocuments();
        documents.forEach(doc -> vectorStore.deleteByDocId(doc.getId()));
        engine.ingestBatch(documents);
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
                       EpisodicMemory episodicMemory,
                       UserProfileStore userProfileStore,
                       SimpleRAGEngine ragEngine,
                       MemoryConsolidator memoryConsolidator) {
        return new Agent(
                Agent.Config.builder().memoryWindow(16).ragTopK(3).longTermRecallTopK(3)
                        .consolidationEveryTurns(3).build(),
                toolRegistry, intentRouter, agentGraph,
                shortTermMemory, episodicMemory, userProfileStore, ragEngine,
                memoryConsolidator
        );
    }
}
