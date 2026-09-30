package yumefusaka.envoymart.aiservice.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.milvus.v2.MilvusV2EmbeddingStore;
import io.milvus.v2.common.ConsistencyLevel;
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
import yumefusaka.envoymart.agent.memory.ShortTermMemoryStore;
import yumefusaka.envoymart.agent.rag.*;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.memory.LlmMemoryConsolidator;
import yumefusaka.envoymart.aiservice.flow.AfterSaleFlow;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeCorpus;
import yumefusaka.envoymart.aiservice.knowledge.KnowledgeGraphBuilder;
import yumefusaka.envoymart.aiservice.rag.GraphEvidenceRetriever;
import yumefusaka.envoymart.aiservice.rag.LangChain4jEmbeddingService;
import yumefusaka.envoymart.aiservice.rag.MilvusVectorStore;
import yumefusaka.envoymart.aiservice.llm.LangChain4jLLMProvider;
import yumefusaka.envoymart.aiservice.tool.CancelOrderTool;
import yumefusaka.envoymart.aiservice.tool.InteractionCheckTool;
import yumefusaka.envoymart.aiservice.tool.LogisticsTool;
import yumefusaka.envoymart.aiservice.tool.OrderTool;
import yumefusaka.envoymart.aiservice.tool.ProductTool;
import io.micrometer.core.instrument.MeterRegistry;
import yumefusaka.envoymart.aiservice.tool.MicrometerToolCallListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 框架的 Spring 配置 —— 将自研 agent-core 组件注入 Spring 容器。
 * <p>
 * 所有组件都可替换：切换 MockLLMProvider → LangChain4jLLMProvider 即可接入真实模型。
 * <p>
 * <b>这里刻意不用 langchain4j-spring-boot4-starter</b>，而是手工构造模型实例：
 * 一是本项目只需要核心库（它零 Spring 依赖），二是装配方式与本文件既有的手工风格一致，
 * 三是避开了 starter 当前所处的 beta 线与其 POM 里 pin 的 Spring Boot 版本。
 */
@Configuration
public class AiAgentConfig {

    // ==================== 模型接入 ====================

    /**
     * 对话模型。留空 API Key 时下面整个 bean 不创建，服务回退到 {@link MockLLMProvider}，
     * 保证本地无 Key 也能启动并跑通链路。
     */
    @Bean
    @ConditionalOnExpression("'${envoymart.llm.api-key:}'.length() > 0")
    public ChatModel chatModel(@Value("${envoymart.llm.api-key}") String apiKey,
                               @Value("${envoymart.llm.base-url}") String baseUrl,
                               @Value("${envoymart.llm.model}") String model,
                               @Value("${envoymart.llm.timeout-ms:60000}") long timeoutMs,
                               @Value("${envoymart.llm.thinking:}") String thinking) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofMillis(timeoutMs))
                .customParameters(thinkingParameters(thinking))
                .build();
    }

    /**
     * 流式对话模型。
     * <p>
     * {@code accumulateToolCallId(false)} 是接 DeepSeek / Qwen 这类端点的必需项：
     * 它们在每个 chunk 里都携带完整的 tool call id，默认的累加行为会把 id 重复拼接，
     * 导致回填的工具结果对不上请求。
     */
    @Bean
    @ConditionalOnExpression("'${envoymart.llm.api-key:}'.length() > 0")
    public StreamingChatModel streamingChatModel(@Value("${envoymart.llm.api-key}") String apiKey,
                                                 @Value("${envoymart.llm.base-url}") String baseUrl,
                                                 @Value("${envoymart.llm.model}") String model,
                                                 @Value("${envoymart.llm.timeout-ms:60000}") long timeoutMs,
                                                 @Value("${envoymart.llm.thinking:}") String thinking) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofMillis(timeoutMs))
                .accumulateToolCallId(false)
                .customParameters(thinkingParameters(thinking))
                .build();
    }

    /**
     * DeepSeek 默认开启思考模式，会返回 reasoning_content 并占用输出 token。
     * Agent 的调用多为分类/规划/合成，不需要深度推理，关掉以降低延迟与成本。
     */
    private Map<String, Object> thinkingParameters(String thinking) {
        return (thinking == null || thinking.isBlank())
                ? Map.of()
                : Map.of("thinking", Map.of("type", thinking));
    }

    /**
     * 向量化模型 —— 与对话模型分开配置：两者可以来自不同供应商。
     * DeepSeek 只有 Chat Completions、没有 Embeddings 端点，所以向量化留在百炼。
     */
    @Bean
    @ConditionalOnExpression("'${envoymart.embedding.api-key:}'.length() > 0")
    public EmbeddingModel embeddingModel(@Value("${envoymart.embedding.api-key}") String apiKey,
                                         @Value("${envoymart.embedding.base-url}") String baseUrl,
                                         @Value("${envoymart.embedding.model}") String model,
                                         @Value("${envoymart.embedding.dimension:1024}") int dimension,
                                         @Value("${envoymart.embedding.timeout-ms:30000}") long timeoutMs) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .dimensions(dimension)
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * 配了模型 Key 就走 LangChain4j 接入层；没配则回退 Mock。
     * <p>
     * 条件按<b>配置项</b>判断而不是 {@code @ConditionalOnBean(ChatModel.class)}：
     * 后者在同一个配置类内依赖 bean 定义的注册顺序，评估可能早于 chatModel 注册，结果不可靠。
     */
    @Bean
    @ConditionalOnExpression("'${envoymart.llm.api-key:}'.length() > 0")
    public LLMProvider langChain4jLLMProvider(ChatModel chatModel,
                                              @Qualifier("streamingChatModel") StreamingChatModel streamingChatModel,
                                              ToolRegistry toolRegistry, LLMConfig llmConfig,
                                              MeterRegistry meterRegistry) {
        return new LangChain4jLLMProvider(chatModel, streamingChatModel, toolRegistry, llmConfig, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(LLMProvider.class)
    public LLMProvider mockLLMProvider() {
        return new MockLLMProvider();
    }

    @Bean
    public LLMConfig llmConfig(@Value("${envoymart.llm.model:mock}") String model,
                               @Value("${envoymart.llm.temperature:0.7}") double temperature,
                               @Value("${envoymart.llm.max-tokens:2048}") int maxTokens) {
        return LLMConfig.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    // ==================== 工具 ====================

    /**
     * 工具注册表 —— 观测点挂在这一层。
     * <p>
     * 计划节点、ReAct 循环、MCP 三条来路的工具调用最终都汇到 {@code ToolRegistry.execute}，
     * 埋点放这里才能一次覆盖全部；挂在某一条路的实现上会漏掉其余路径。
     */
    @Bean
    public ToolRegistry toolRegistry(OrderClient orderClient, ProductClient productClient,
                                     KnowledgeClient knowledgeClient,
                                     MeterRegistry meterRegistry) {
        ToolRegistry registry = new ToolRegistry(new MicrometerToolCallListener(meterRegistry));
        registry.registerAll(List.of(
                new OrderTool(orderClient),
                new LogisticsTool(orderClient),
                new ProductTool(productClient),
                new CancelOrderTool(orderClient),
                new InteractionCheckTool(knowledgeClient)
        ));
        return registry;
    }

    // ==================== 记忆 ====================

    /**
     * 会话窗口 —— 内存里留一份作为读缓存，同时落 Redis。
     * <p>
     * 落盘之后才有了两件事：<b>重启不丢上下文</b>，以及<b>多实例时同一用户落到哪个实例都接得上</b>。
     * 在此之前，业务层无状态可水平扩、AI 层却因这一处内存状态扩不了——这个不对称会让
     * "支持分布式"在架构上站不住。
     */
    @Bean
    public ShortTermMemory shortTermMemory(ShortTermMemoryStore shortTermMemoryStore) {
        return new ShortTermMemory(16, shortTermMemoryStore);
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

    /**
     * 知识图谱抽取器。与索引一样属于「从语料派生出的一份结构」，
     * 由 {@link yumefusaka.envoymart.aiservice.knowledge.KnowledgeIndexer} 在重建时统一驱动。
     */
    @Bean
    public KnowledgeGraphBuilder knowledgeGraphBuilder(LLMProvider llmProvider, LLMConfig llmConfig,
                                                       KnowledgeClient knowledgeClient,
                                                       ProductClient productClient) {
        return new KnowledgeGraphBuilder(llmProvider, llmConfig, knowledgeClient, productClient);
    }

    // ==================== 向量化与向量库 ====================

    /** 配了模型 Key 就用百炼/OpenAI 的 EmbeddingModel（语义召回才有意义）。 */
    @Bean
    @ConditionalOnExpression("'${envoymart.embedding.api-key:}'.length() > 0")
    public EmbeddingService langChain4jEmbeddingService(EmbeddingModel embeddingModel) {
        return new LangChain4jEmbeddingService(embeddingModel);
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

    /** 知识库：生产用 Milvus。 */
    @Bean("knowledgeVectorStore")
    @Primary
    @Profile("milvus")
    public VectorStore milvusKnowledgeVectorStore(
            EmbeddingService embeddingService,
            @Value("${envoymart.milvus.host:127.0.0.1}") String host,
            @Value("${envoymart.milvus.port:19530}") int port,
            @Value("${envoymart.embedding.dimension:1024}") int dimension) {
        return new MilvusVectorStore(
                newMilvusStore("envoymart_knowledge", host, port, dimension), embeddingService);
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
    public VectorStore milvusMemoryVectorStore(
            EmbeddingService embeddingService,
            @Value("${envoymart.milvus.host:127.0.0.1}") String host,
            @Value("${envoymart.milvus.port:19530}") int port,
            @Value("${envoymart.embedding.dimension:1024}") int dimension) {
        return new MilvusVectorStore(
                newMilvusStore("envoymart_memory", host, port, dimension), embeddingService);
    }

    /**
     * 构造一个 Milvus 向量库。
     * <p>
     * <b>一致性等级用 Strong。</b>入库前要先按 docId 删旧切片（见下面 ragEngine 的说明），
     * 而删除的可见性受一致性等级约束——低于 Strong 时删掉的条目可能仍被检索到，
     * 表现为「重启后同一篇文档在结果里出现多次」这个本已修掉的缺陷换个形式回来。
     * 本项目语料只有十几篇，Strong 的代价可以忽略。
     */
    private MilvusV2EmbeddingStore newMilvusStore(String collection, String host, int port, int dimension) {
        return MilvusV2EmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collection)
                .dimension(dimension)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
    }

    // ==================== 检索 ====================

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

    /**
     * 切分策略 —— 线上与评测共用同一实现，避免"测的是一套、跑的是另一套"。
     * <p>
     * {@link StructuralSplitter} 按文档结构（章 → 条 → 段 → 句）分层下钻，超限才切开，
     * 并给每片拼上位置前缀。相较定长硬切，实测在长文档上：句末标点 30% → 100%、
     * 语义完整性 17/18 → 18/18、端到端完整召回率 73.3% → 85.0%（配合切片级检索）。
     * <p>
     * 参数 512/40 来自调参，其中 minChars 是关键：它把"第一章 XXX"这类<b>无内容的标题碎片</b>
     * 合并掉。碎片会被向量化并挤占 topK 名额——实测 minChars=0（不合并）时完整召回率
     * 反而从 81.7% 掉到 72.5%。
     * <p>
     * <b>参数取值不写在这里，走 {@link StructuralSplitter#standard()}。</b>
     * knowledge-service 建切片表时用的是同一份定义。两边各写一遍的话，同一篇文档会切出
     * 两组 chunkId，症状是「检索命中了、但点开引用 404」，而两边日志都正常。
     */
    @Bean
    public TextSplitter textSplitter() {
        return StructuralSplitter.standard();
    }

    /**
     * 检索器 —— <b>切片级</b>：BM25 与向量路在同一粒度上融合。
     * <p>
     * 与文档级的区别只在 RRF 的归一 key（chunkId vs docId）：长文档的多个相关切片
     * 可以各自占据候选位，而不是整篇文档只争一个名额。实测在长文档语料上，
     * 切片级比文档级高 7.5pp（85.0% vs 77.5%），也比仅向量路高 3.3pp。
     * <p>
     * 切分是纯函数，这里与 {@code ragEngine} 各自用同一个 splitter 切一遍，得到的是
     * 同一组切片——这样装配上不必让 retriever 反过来依赖 engine（那会形成循环依赖）。
     * <p>
     * <b>第三路（图谱）通过 {@code envoymart.rag.graph-recall.enabled} 开关接入</b>，
     * 关掉时传 null，检索行为与只有两路时逐字节一致。留这个开关是为了能当场演示
     * 「同一句话，开与关各答一次」——这条路的增益必须能被看见或被证否，
     * 而不是靠一段架构描述让人相信它有用。
     */
    @Bean
    public HybridRetriever retriever(@Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                                     Reranker reranker,
                                     TextSplitter textSplitter,
                                     KnowledgeCorpus corpus,
                                     KnowledgeClient knowledgeClient,
                                     @Value("${envoymart.rag.graph-recall.enabled:true}") boolean graphRecall) {
        List<DocumentChunk> chunks = corpus.documents().stream()
                .flatMap(doc -> textSplitter.split(doc).stream())
                .toList();
        return HybridRetriever.overChunks(vectorStore, chunks, reranker,
                graphRecall ? new GraphEvidenceRetriever(knowledgeClient) : null);
    }

    /**
     * 检索引擎的装配。
     * <p>
     * <b>这里只构造，不灌数据。</b>语料来自 knowledge-service，入库是
     * {@link yumefusaka.envoymart.aiservice.knowledge.KnowledgeIndexer} 的职责——
     * 「启动建索引」与「管理台点重建索引」必须走同一条路径，否则两条路径会各自演化，
     * 而它们的差异只在管理动作里暴露：手工重建过的索引与重启后自动重建的索引不是同一份。
     */
    @Bean
    public SimpleRAGEngine ragEngine(@Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                                     Retriever retriever,
                                     TextSplitter textSplitter) {
        return new SimpleRAGEngine(vectorStore, retriever, textSplitter);
    }

    // ==================== 执行图 ====================

    /**
     * 确定性流程注册 —— 业务判定由代码完成，不交给模型自由发挥。
     */
    @Bean
    public FlowRegistry flowRegistry(OrderClient orderClient) {
        FlowRegistry registry = new FlowRegistry();
        // 售后流程只取结论、不自己判：判定权在 order-service 的政策引擎，
        // 两处各判一次的代价是同一个订单两个答案
        registry.registerAll(List.of(new AfterSaleFlow(orderClient)));
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
