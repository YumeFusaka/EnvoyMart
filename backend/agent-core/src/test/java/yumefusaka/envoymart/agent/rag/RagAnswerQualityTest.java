package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>端到端回答质量评测</b> —— 补上"检索指标"与"用户实际拿到什么"之间缺的那一段。
 *
 * <h3>它回答什么问题</h3>
 * {@link RetrievalComparisonTest} 测的是 <b>Hit Rate / MRR / NDCG</b>，那是<b>中间指标</b>：
 * 召回到了正确的文档，模型仍可能答错。简历上写着"引入向量召回后 Hit Rate@3 由 0.63 提升至 0.83"，
 * 面试官下一句必然是"<b>那回答质量提升了多少</b>"——这一条就是为那句话准备的。
 *
 * <h3>为什么用 LLM 当裁判</h3>
 * 逐条写参考答案成本高、且参考答案本身也会成为偏差来源。这里改成<b>只要问题、不要答案</b>：
 * 把「标准文档」（夹具里标注的相关文档正文）连同系统回答一起交给裁判，让它判断
 * <b>回答是否忠实于该文档、是否回答了问题</b>。这是 RAGAS 一类的常规做法，
 * 优点是不依赖人工撰写参考文本。
 *
 * <h3>它测不到的（必须一起说，否则就是过度声称）</h3>
 * <ul>
 *   <li><b>裁判自己也会错</b>，而且同族模型当裁判有自我偏好。这里的分数只用于<b>横向比较</b>
 *       （同一裁判、同一批问题、只改检索配置），不能当作"回答质量的绝对值"</li>
 *   <li>样本 21 条，够看方向、不够做统计显著性</li>
 *   <li>这里只跑 RAG 那条路径，<b>不含意图路由、工具调用与多轮记忆</b>——
 *       也就是说它不是"整个 Agent 的回答质量"，而是"检索配置对回答质量的影响"</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>
 * RUN_RAG_ANSWER_QUALITY=true DASHSCOPE_API_KEY=xxx \
 *   LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1 LLM_MODEL=qwen-plus \
 *   mvn -pl agent-core test -Dtest=RagAnswerQualityTest
 * </pre>
 * 会真实产生约 2 × 配置数 × 样本条 的 API 调用，所以用显式开关控制，不进 CI。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RAG_ANSWER_QUALITY", matches = "true")
class RagAnswerQualityTest {

    /** 每个分档取几条 —— 21 条是"够看方向"与"调用量可接受"之间的折中 */
    private static final int PER_GROUP = 7;
    /** 与线上 {@code Agent.Config.ragTopK} 对齐 */
    private static final int ONLINE_TOP_K = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 {@code Agent.Config.defaultSystemPrompt} 保持一致 */
    private static final String BASE_SYSTEM_PROMPT =
            "你是一个智能电商助手，帮助用户选购商品、查询订单、解答售后问题。";

    /** 裁判提示词。**要求只输出 JSON**，否则解析会被模型的客套话带偏 */
    private static final String JUDGE_PROMPT = """
            你在评估一个电商客服 RAG 系统的回答质量。

            我会给你【用户问题】、【知识库中的标准文档】、【系统生成的回答】。请打两个分：

            1. faithfulness（忠实度，1-5）：回答中的事实性内容能否在【标准文档】里找到依据？
               编造文档之外的细节、或与文档冲突，都要扣分。文档没提到的内容，回答里出现即算不忠实。
            2. relevance（相关性，1-5）：回答是否直接、完整地回答了用户的问题？
               答非所问得 1 分；答到了但绕弯或不完整得 3 分；直接且完整得 5 分。

            只输出一行 JSON，不要任何解释、不要 markdown 代码块：
            {"faithfulness": <1-5>, "relevance": <1-5>}
            """;

    @Test
    void 检索配置对回答质量的影响() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String baseUrl = System.getenv("LLM_BASE_URL");
        String model = System.getenv("LLM_MODEL");
        assertThat(apiKey).as("需要 DASHSCOPE_API_KEY（向量化与重排）").isNotBlank();
        assertThat(baseUrl).as("需要 LLM_BASE_URL（对话）").isNotBlank();
        assertThat(model).as("需要 LLM_MODEL（对话）").isNotBlank();

        OpenAiChatClient chat = new OpenAiChatClient(baseUrl, apiKey, model);

        // ---- 建三条检索链路，与 RetrievalComparisonTest 完全一致，保证两处结论可对话 ----
        Retriever bm25Only = new HybridRetriever(
                new InMemoryVectorStore(new SimpleEmbeddingService()), RetrievalFixtures.DOCS);

        InMemoryVectorStore vectorStore = new InMemoryVectorStore(
                new DashScopeEmbeddingService(apiKey, "text-embedding-v4"));
        DashScopeReranker reranker = new DashScopeReranker(
                apiKey, "gte-rerank-v2", null, java.time.Duration.ofSeconds(30));
        Retriever hybrid = new HybridRetriever(vectorStore, RetrievalFixtures.DOCS);
        Retriever hybridWithRerank = new HybridRetriever(vectorStore, RetrievalFixtures.DOCS, reranker);
        new SimpleRAGEngine(vectorStore, hybrid,
                RetrievalFixtures.CHUNK_SIZE, RetrievalFixtures.CHUNK_OVERLAP)
                .ingestBatch(RetrievalFixtures.DOCS);

        List<RetrievalEvaluator.EvalCase> samples = sample();
        System.out.printf("%n========== 端到端回答质量（样本 %d 条，裁判 %s）==========%n", samples.size(), model);

        // 每条记录：配置名 -> 得分累计；顺带累计 token 供成本模型使用
        Map<String, Score> scores = new LinkedHashMap<>();
        run(chat, scores, "仅关键词", bm25Only, ONLINE_TOP_K, samples);
        run(chat, scores, "混合(BM25+向量)", hybrid, ONLINE_TOP_K, samples);
        run(chat, scores, "混合+重排", hybridWithRerank, ONLINE_TOP_K, samples);
        // 顺带回答"线上该取 topK 几"：只对线上配置再跑一遍 @5
        run(chat, scores, "混合+重排 @5", hybridWithRerank, 5, samples);

        System.out.println();
        System.out.printf("%-18s %12s %12s %12s %10s%n",
                "配置", "忠实度(1-5)", "相关性(1-5)", "回答均长", "token/条");
        System.out.println("-".repeat(70));
        scores.forEach((name, s) -> System.out.printf("%-18s %12.2f %12.2f %12.0f %10d%n",
                name, s.avgFaithfulness(), s.avgRelevance(), s.avgAnswerChars(), s.tokensPerCase()));
        System.out.println("======================================================================");

        assertThat(scores).as("四个配置都应产出分数").hasSize(4);
        assertThat(scores.get("混合+重排").caseCount)
                .as("全部样本都应完成评分，缺一条就说明有调用失败")
                .isEqualTo(samples.size());
    }

    /** 跑一个配置：检索 → 组装 prompt → 生成回答 → 裁判打分。 */
    private void run(OpenAiChatClient chat, Map<String, Score> scores,
                     String name, Retriever retriever, int topK,
                     List<RetrievalEvaluator.EvalCase> samples) {
        Score score = new Score();
        Map<String, Document> docById = new LinkedHashMap<>();
        RetrievalFixtures.DOCS.forEach(d -> docById.put(d.getId(), d));

        for (RetrievalEvaluator.EvalCase sample : samples) {
            List<DocumentChunk> retrieved = retriever.retrieve(sample.query(), topK);

            // 上下文按线上 buildSystemPrompt 的「## 相关知识」段落拼 ——
            // 这里若与线上不一致，测的就不是线上那个系统了
            StringBuilder systemPrompt = new StringBuilder(BASE_SYSTEM_PROMPT);
            systemPrompt.append("\n\n## 相关知识\n");
            for (int i = 0; i < retrieved.size(); i++) {
                systemPrompt.append(i + 1).append(". ").append(retrieved.get(i).getContent()).append("\n");
            }
            systemPrompt.append("\n请基于以上知识回答用户问题；知识里没有的内容不要编造。");

            String answer;
            try {
                OpenAiChatClient.Reply reply = chat.chat(systemPrompt.toString(), sample.query(), 0.2);
                answer = reply.content();
                score.addTokens(reply.totalTokens());
            } catch (Exception e) {
                System.out.printf("  [跳过] 生成失败 query=%s：%s%n", sample.query(), e.getMessage());
                continue;
            }
            score.addAnswer(answer);

            // 标准文档 = 夹具里标注的相关文档。裁判同时看它和回答，判断忠实度与相关性
            String reference = sample.relevantDocIds().stream()
                    .map(docById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(doc -> doc.getTitle() + "：" + doc.getContent())
                    .reduce("", (a, b) -> a + "\n" + b);

            judge(chat, score, sample.query(), reference, answer);
        }
        scores.put(name, score);
    }

    private void judge(OpenAiChatClient chat, Score score,
                       String query, String reference, String answer) {
        String userMessage = "【用户问题】\n" + query
                + "\n\n【知识库中的标准文档】\n" + reference
                + "\n\n【系统生成的回答】\n" + answer;
        try {
            OpenAiChatClient.Reply reply = chat.chat(JUDGE_PROMPT, userMessage, 0.0);
            score.addTokens(reply.totalTokens());
            JsonNode node = MAPPER.readTree(stripCodeFence(reply.content()));
            score.addScore(node.path("faithfulness").asInt(0), node.path("relevance").asInt(0));
        } catch (Exception e) {
            // 裁判失败只记账不中断：一条判不出来不该让整轮作废，但要能从"完成条数"看出来
            System.out.printf("  [裁判失败] query=%s：%s%n", query, e.getMessage());
        }
    }

    /** 模型偶尔仍会包 ```json 代码块，容错一下；解析失败会在调用处被记数。 */
    private static String stripCodeFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    /**
     * 从三档里各取 {@link #PER_GROUP} 条。
     * <p>
     * 用固定步长而不是随机：评测要可复现，随机抽样换一次运行就换一批题目，
     * 跨配置的对比也就失去意义。步长取样还能覆盖到每一档的首尾，
     * 不像 {@code subList(0, n)} 那样只看开头几条——夹具是按主题成簇排的，只看开头等于只测几个主题。
     */
    private static List<RetrievalEvaluator.EvalCase> sample() {
        List<RetrievalEvaluator.EvalCase> picked = new ArrayList<>();
        picked.addAll(stride(RetrievalFixtures.LEXICAL_CASES));
        picked.addAll(stride(RetrievalFixtures.PARAPHRASE_CASES));
        picked.addAll(stride(RetrievalFixtures.HARD_CASES));
        return picked;
    }

    private static List<RetrievalEvaluator.EvalCase> stride(List<RetrievalEvaluator.EvalCase> cases) {
        int step = Math.max(1, cases.size() / PER_GROUP);
        List<RetrievalEvaluator.EvalCase> picked = new ArrayList<>();
        for (int i = 0; i < cases.size() && picked.size() < PER_GROUP; i += step) {
            picked.add(cases.get(i));
        }
        return picked;
    }

    /** 一个配置的累计结果。 */
    private static final class Score {
        private int caseCount;
        private double faithfulnessSum;
        private double relevanceSum;
        private long answerChars;
        private long tokens;

        void addAnswer(String answer) {
            answerChars += answer == null ? 0 : answer.length();
        }

        void addScore(int faithfulness, int relevance) {
            // 解析失败会得到 0 分，不计入——否则一个格式错误会把均分拉低，
            // 而那与"回答质量"无关
            if (faithfulness <= 0 || relevance <= 0) {
                return;
            }
            faithfulnessSum += faithfulness;
            relevanceSum += relevance;
            caseCount++;
        }

        void addTokens(int count) {
            tokens += count;
        }

        double avgFaithfulness() {
            return caseCount == 0 ? 0 : faithfulnessSum / caseCount;
        }

        double avgRelevance() {
            return caseCount == 0 ? 0 : relevanceSum / caseCount;
        }

        double avgAnswerChars() {
            return caseCount == 0 ? 0 : (double) answerChars / caseCount;
        }

        /** 每条样本的平均 token —— 成本模型的输入 */
        long tokensPerCase() {
            return caseCount == 0 ? 0 : tokens / caseCount;
        }
    }
}
