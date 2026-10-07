package yumefusaka.envoymart.aiservice.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.Agent;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.agent.rag.GroundingEvaluator;
import yumefusaka.envoymart.agent.rag.GroundingFixtures;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 回答质量的<b>线上真跑</b>评测 —— 拿夹具里的问题重新问一遍真实 Agent。
 * <p>
 * 这里跑的是当前模型、当前知识库和当前图谱，完成后落盘为只读快照。
 * <p>
 * <b>为什么是异步 + 单飞。</b>24 条用例串行调模型，同步请求会超时，而并发跑多个评测
 * 除了互相抢配额没有别的作用。所以：一个时刻只允许一个任务，重复触发直接返回当前状态，
 * 前端轮询进度。
 * <p>
 * <b>每条用例一个全新的 userId + sessionId。</b>不是洁癖：长期记忆是按 userId 存的，
 * 同一个人连着问 24 个问题，后面几轮的 prompt 里会掺进前面几轮的画像与情节——
 * 那样测出来的就不是"这批问题答得怎么样"，而是"一个越问越熟的会话答得怎么样"。
 */
@Slf4j
@Service
public class GroundingLiveEvalService {

    private static final Path SNAPSHOT_FILE = Path.of("data", "eval", "production-grounding.json");

    /**
     * 一条用例的真跑结果。
     *
     * @param question 问题原文。判定结果里没有它（判定层不需要），但逐条明细要显示——
     *                 只给一个 G-01，读者没法核对这一条在问什么
     * @param outcome  对真实回答做确定性判定后的结果
     * @param answer   这一轮的真实回答，报告页要能逐字读——指标是摘要，答案才是证据
     * @param error    单条失败的原因。一条失败不该让整轮作废，但也不能悄悄消失：
     *                 它会把这一条从分母里摘掉，不写出来就成了"少算了一条"
     */
    public record CaseResult(String question, GroundingEvaluator.CaseOutcome outcome, String answer,
                             int evidenceCount, long latencyMs, String error) {
    }

    /**
     * 一次真跑的整体状态。<b>可序列化进响应体</b>，字段名即前端读到的名字。
     *
     * @param status          IDLE / RUNNING / COMPLETED
     * @param completedCases  已完成（含失败）的条数，前端据此画进度
     * @param metrics         只有全部跑完才有值：中途的指标是"越跑越像"的假数字
     */
    public record LiveRun(String status, String startedAt, String finishedAt, int totalCases,
                          int completedCases, int failedCases, String currentQuestion, String userId,
                          GroundingEvaluator.Metrics metrics, List<CaseResult> cases) {

        static LiveRun idle(int totalCases) {
            return new LiveRun("IDLE", null, null, totalCases, 0, 0, null, null, null, List.of());
        }
    }

    private final Agent agent;
    private final ObjectMapper objectMapper;

    /** 单飞闸：评测要花 token，并发触发只会有多个任务抢同一份配额 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次结果。volatile 只赋值不原地改，与检索评测的快照同一套发布方式 */
    private volatile LiveRun latest;

    public GroundingLiveEvalService(Agent agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    public LiveRun current() {
        LiveRun current = latest;
        if (current != null) {
            return current;
        }
        if (Files.exists(SNAPSHOT_FILE)) {
            try {
                LiveRun snapshot = objectMapper.readValue(Files.readString(SNAPSHOT_FILE, StandardCharsets.UTF_8),
                        new TypeReference<>() { });
                latest = snapshot;
                return snapshot;
            } catch (Exception e) {
                log.warn("[AI] 回答质量真实快照读取失败：{}", e.getMessage());
            }
        }
        return LiveRun.idle(GroundingFixtures.CASES.size());
    }

    /**
     * 启动一次真跑。已在跑时直接返回当前状态（不排队、不报错）——
     * 连点两次按钮的用户想要的是"看着它跑完"，不是"排上两个任务"。
     */
    public LiveRun start() {
        if (!running.compareAndSet(false, true)) {
            return current();
        }
        LiveRun seed = new LiveRun("RUNNING", OffsetDateTime.now().toString(), null,
                GroundingFixtures.CASES.size(), 0, 0, null, null, null, List.of());
        latest = seed;
        Thread worker = new Thread(this::execute, "grounding-live-eval");
        worker.setDaemon(true);
        worker.start();
        return seed;
    }

    private void execute() {
        // 每次运行一个独立的用户：上一轮的长期记忆不该影响这一轮
        String userId = "eval-" + System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>();
        int failed = 0;
        try {
            for (GroundingFixtures.Case fixtureCase : GroundingFixtures.CASES) {
                publishRunning(userId, results, fixtureCase);
                CaseResult result = runCase(userId, fixtureCase);
                if (result.error() != null) {
                    failed++;
                }
                results.add(result);
            }
            // 判定在每条用例里已经算完了，这里只做汇总——失败的条目从分母里摘掉，
            // 它们已经被 failedCases 单独计数并在响应体里下发，不会凭空消失
            List<GroundingEvaluator.CaseOutcome> outcomes = results.stream()
                    .map(CaseResult::outcome)
                    .filter(java.util.Objects::nonNull)
                    .sorted(GroundingEvaluator.byId())
                    .toList();
            GroundingEvaluator.Metrics metrics = GroundingEvaluator.aggregate(outcomes);
            latest = new LiveRun("COMPLETED", latest.startedAt(), OffsetDateTime.now().toString(),
                    GroundingFixtures.CASES.size(), results.size(), failed, null, userId, metrics,
                    List.copyOf(results));
            persist(latest);
            log.info("[AI] 回答质量真跑完成：cases={} 失败={} 幻觉率={} 引用准确率={} 拒答准确率={} 多跳命中率={}",
                    results.size(), failed, metrics.hallucinationRate(), metrics.citationAccuracy(),
                    metrics.refusalAccuracy(), metrics.multiHopHitRate());
        } catch (RuntimeException e) {
            // 整轮崩掉（例如 Agent 初始化失败）也要留下一份可读的状态，而不是永远停在 RUNNING
            log.error("[AI] 回答质量真跑中断", e);
            latest = new LiveRun("COMPLETED", latest.startedAt(), OffsetDateTime.now().toString(),
                    GroundingFixtures.CASES.size(), results.size(), failed, null, userId, null,
                    List.copyOf(results));
        } finally {
            running.set(false);
        }
    }

    private void persist(LiveRun report) {
        try {
            Files.createDirectories(SNAPSHOT_FILE.getParent());
            Files.writeString(SNAPSHOT_FILE,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[AI] 回答质量真实快照落盘失败：{}", e.getMessage());
        }
    }

    private void publishRunning(String userId, List<CaseResult> done, GroundingFixtures.Case fixtureCase) {
        latest = new LiveRun("RUNNING", latest.startedAt(), null, GroundingFixtures.CASES.size(),
                done.size(), 0, fixtureCase.question(), userId, null, List.copyOf(done));
    }

    /**
     * 跑一条用例。
     * <p>
     * <b>异常在这里被兜住而不是往上抛</b>：模型超时、限流、知识库抖一下，都是单条的事，
     * 让它们把整轮评测带走，等于"网络抖一次就得重新点一遍按钮"。
     */
    private CaseResult runCase(String userId, GroundingFixtures.Case fixtureCase) {
        // 每条用例一个会话：多轮上下文在这里是噪声，不是能力
        String sessionId = "grounding-eval-" + fixtureCase.id() + "-" + System.currentTimeMillis();
        long started = System.currentTimeMillis();
        try {
            // 无确认令牌：评测问的都是知识与检索类问题，不碰高危操作
            Agent.AgentResponse response = agent.chat(userId, sessionId, fixtureCase.question(), null);
            long latency = System.currentTimeMillis() - started;
            List<DocumentChunk> evidence =
                    response.getKnowledge() == null ? List.of() : response.getKnowledge();
            boolean toolEvidence = response.getToolExecutions() != null
                    && !response.getToolExecutions().isEmpty();
            EvidenceGate.Level level = response.getEvidenceLevel() == null
                    ? EvidenceGate.Level.NONE : response.getEvidenceLevel();

            GroundingEvaluator.Sample sample = new GroundingEvaluator.Sample(fixtureCase.id(),
                    fixtureCase.kind(), fixtureCase.expectRefuse(), fixtureCase.mustMention(),
                    evidence, response.getReply(), level, toolEvidence);
            GroundingEvaluator.CaseOutcome outcome =
                    GroundingEvaluator.evaluate(List.of(sample)).cases().getFirst();
            return new CaseResult(fixtureCase.question(), outcome, response.getReply(),
                    evidence.size(), latency, null);
        } catch (RuntimeException e) {
            log.warn("[AI] 回答质量真跑单条失败 id={} 问题={}", fixtureCase.id(), fixtureCase.question(), e);
            return new CaseResult(fixtureCase.question(), null, null, 0,
                    System.currentTimeMillis() - started,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
