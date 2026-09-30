package yumefusaka.envoymart.agent.rag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 回答质量评测的夹具 —— <b>真实模型输出</b>的快照 + 标注。
 * <p>
 * 数据在 {@code resources/eval/grounding-fixtures.json}，由
 * {@code frontend/scripts/capture-grounding-fixtures.mjs} 采集。放在 main 而不是测试目录，
 * 因为除 CI 门禁（{@code GroundingQualityTest}）之外，ai-service 的 live 评测
 * 也要读同一批问题（{@code POST /ai/admin/eval/grounding/run}）——
 * 「离线重放」与「线上真跑」必须问同样的问题、用同一套判定，否则两组数字没法放在一起看。
 * <p>
 * <b>这份夹具的定位必须说清楚，否则它会被读成它没有的意思：</b>
 * <ul>
 *   <li>{@link Case#answer()} <b>不是标准答案</b>，是某一时刻模型的真实输出。它可能答错、
 *       可能漏标引用——正因如此它才适合当回归基线：判定链在这些真实输出上给出什么分数，
 *       改动判定逻辑时分数动了没有，是它唯一要回答的问题。</li>
 *   <li>它测的是<b>判定层</b>（拒答门、引用校验、冲突呈现）与<b>答案形态</b>，
 *       不是"模型有多聪明"。模型质量要靠 live 评测重跑，两者指标同名同算法，
 *       差别只在数据来源——报告里必须标出来源，不许混着讲。</li>
 *   <li>语料换代或提示词大改之后要<b>重新采集</b>。夹具过期不会报错，只会安静地
 *       测一个已经不存在的系统。</li>
 * </ul>
 * <p>
 * <b>标注里的 {@code mustMention} 逐字取自事实源文档</b>，不是从模型答案里抄的。
 * 这一条是引用准确率能成立的前提：拿模型自己的话当锚点，等于让它自己给自己判分。
 */
public final class GroundingFixtures {

    /**
     * 一条用例。
     *
     * @param id              稳定标识（报告页逐条明细按它排序，重新采集后仍对得上）
     * @param question        问题原文
     * @param kind            用例类型，决定它进哪一项指标的分母
     * @param expectRefuse    拒答门的期望判定：库里没有的题该判"拒答侧"
     * @param mustMention     锚点词，<b>逐字取自事实源文档</b>。引用准确率要求
     *                        "句中出现的锚点必须能在它引用的证据里逐字找到"；
     *                        多跳命中率要求"这些锚点全部出现在答案里"
     * @param evidence        本轮真实召回的切片（含分数、来源、是否重排）
     * @param answer          本轮真实回答
     * @param evidenceLevel   本轮拒答门的判定，由 Agent 随证据一并下发
     * @param toolEvidence    本轮是否执行过工具：跑过工具的事实来自工具返回，
     *                        没有知识库引用是正常的，不该计进幻觉
     * @param unsupportedClaims 运行时校验报出的未支撑句（用来对照判定链是否改了口径）
     * @param ungrounded      运行时校验是否判定"整篇无依据"
     * @param latencyMs       采集时的端到端耗时，仅供报告页展示，不参与任何指标
     */
    public record Case(String id, String question, Kind kind, boolean expectRefuse,
                       List<String> mustMention, List<DocumentChunk> evidence, String answer,
                       EvidenceGate.Level evidenceLevel, boolean toolEvidence,
                       List<String> unsupportedClaims, boolean ungrounded, long latencyMs) {
    }

    /**
     * 用例类型。
     * <p>
     * {@code UNANSWERABLE} 与另外两类的区别不是难度，而是<b>期望行为不同</b>：
     * 前两类该答，这一类该拒。把它们混在一张表里算总分，等于用"回答率"奖赏编造。
     */
    public enum Kind {
        /** 语料里有据可答 */
        ANSWERABLE,
        /** 语料里没有，正确行为是拒答 */
        UNANSWERABLE,
        /** 结论要跨两篇以上文档才成立——图谱与多路召回存在的理由 */
        MULTI_HOP
    }

    /** 采集时间，进报告页（"这份数字是什么时候的"） */
    public static final String CAPTURED_AT;

    /** 采集时的对话模型标识，进报告页 */
    public static final String MODEL;

    public static final List<Case> CASES;

    static {
        Map<String, Object> raw = load();
        CAPTURED_AT = String.valueOf(raw.getOrDefault("capturedAt", ""));
        MODEL = String.valueOf(raw.getOrDefault("model", ""));
        CASES = ((List<?>) raw.get("cases")).stream().map(GroundingFixtures::toCase).toList();
    }

    private GroundingFixtures() {
    }

    public static List<Case> byKind(Kind kind) {
        return CASES.stream().filter(c -> c.kind() == kind).toList();
    }

    private static Map<String, Object> load() {
        try (InputStream in = GroundingFixtures.class
                .getResourceAsStream("/eval/grounding-fixtures.json")) {
            if (in == null) {
                // 与检索夹具同样的取舍：资产缺失是构建问题，在类加载时就喊出来，
                // 不让报告页显示一份"0 条用例、四项满分"的空评测
                throw new IllegalStateException("缺少评测夹具资源 /eval/grounding-fixtures.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("回答质量夹具读取失败", e);
        }
    }

    private static Case toCase(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        return new Case(
                (String) map.get("id"),
                (String) map.get("question"),
                Kind.valueOf((String) map.get("kind")),
                Boolean.TRUE.equals(map.get("expectRefuse")),
                strings(map.get("mustMention")),
                ((List<?>) map.get("evidence")).stream().map(GroundingFixtures::toChunk).toList(),
                map.get("answer") == null ? "" : String.valueOf(map.get("answer")),
                levelOf(map.get("evidenceLevel")),
                Boolean.TRUE.equals(map.get("toolEvidence")),
                strings(map.get("unsupportedClaims")),
                Boolean.TRUE.equals(map.get("ungrounded")),
                map.get("latencyMs") instanceof Number n ? n.longValue() : 0L);
    }

    /**
     * 证据门判定可能缺席（采集那次没有知识型回答时它是 null）。
     * <p>
     * 缺席记成 {@code NONE} 而不是"最宽松"的那一档：没有判定 = 没有权威依据可给，
     * 与拒答门的语义一致。记成 SUFFICIENT 会让缺数据的用例悄悄变成"该答没答"。
     */
    private static EvidenceGate.Level levelOf(Object raw) {
        if (raw == null) {
            return EvidenceGate.Level.NONE;
        }
        return EvidenceGate.Level.valueOf(String.valueOf(raw));
    }

    private static List<String> strings(Object raw) {
        if (raw == null) {
            return List.of();
        }
        return ((List<?>) raw).stream().map(String::valueOf).toList();
    }

    private static DocumentChunk toChunk(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        return DocumentChunk.builder()
                .chunkId((String) map.get("chunkId"))
                .docId((String) map.get("docId"))
                .title((String) map.get("title"))
                .source((String) map.get("source"))
                .score(map.get("score") instanceof Number n ? n.doubleValue() : null)
                .reranked(Boolean.TRUE.equals(map.get("reranked")))
                .content((String) map.get("content"))
                .build();
    }
}
