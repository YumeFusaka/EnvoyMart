package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答质量评测 —— 幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率。
 * <p>
 * <b>它测的是「判定层」，不是「模型有多聪明」。</b>四项指标全部由确定性逻辑算出：
 * 引用校验（{@link CitationVerifier}）、证据门（{@link EvidenceGate}）、
 * 锚点逐字比对。同一份输入永远给同一个分数——这是它能进 CI 门禁的前提，
 * 也是它相对"模型判官"的优势：模型自评有自我偏好，会给虚假的质量信号。
 * <p>
 * <b>判据（v2，2026-10-01 按真实答案校准）：</b>
 * <ol>
 *   <li><b>开关与运行时闸门同义</b>：答案里出现过至少一处有效引用 = 知识型回答，走逐句判定；
 *       没有引用且没有工具记录 = 整篇无依据；没有引用但有工具记录 = 工具轮次，逐句判据不适用
 *       （事实来自工具返回，本来就没有角标可标——与 {@link CitationVerifier} 同一取舍）。</li>
 *   <li><b>候选句</b> = 含锚点、且不以冒号结尾（「……规则如下：」是引出句，不是断言）。
 *       锚点逐字取自事实源文档，不碰同义词与单位换算——边界见下。</li>
 *   <li><b>引用范围</b>：句子自身的有效引用；自身没有时取<b>所在块</b>（空行分隔的段落，
 *       列表跨空行延续）。中文技术写作常把角标打在整段/整个列表末尾，逐句要求会误伤。</li>
 *   <li><b>有出处</b> = 范围内存在证据把该句的每个锚点都逐字包含。
 *       没有任何引用 → 未支撑；引用里找不到锚点 → 未支撑（引错和没引一样支撑不了），
 *       同时计入引用准确率的分子错误。</li>
 *   <li><b>整篇无依据</b>的该答用例：每个成句（≥12 字，与闸门同一阈值）都是无出处的断言，
 *       全篇计。但只在「该答」的用例上算——库外用例的正确产出就是拒答，
 *       把拒答算成幻觉会把两种东西混成一个数字。</li>
 *   <li><b>不该答的用例只看一件事</b>：拒答门判得对不对。它答了什么不进另外三项的账。</li>
 * </ol>
 * <p>
 * <b>代价与边界（必须跟着数字一起讲）：</b>
 * <ul>
 *   <li>判据是<b>逐字</b>的：锚点词必须在证据或答案里原文出现。它抓得住"引用指错了"，
 *       抓不住"这句话的意思被曲解了"——后者需要语义判断，本项目没有做。</li>
 *   <li>它<b>不能</b>回答"这句话对不对"。"铁剂与左旋多巴间隔 2 小时"是否成立，
 *       取决于事实源文档是否权威，而那是知识库的问题，不是校验层的问题。</li>
 *   <li>拒答准确率测的是<b>门判得对不对</b>（该拒的判没判成拒答侧），
 *       不是"模型服不服从"（门说拒答、模型还是答了）——后者没有确定性判据，
 *       只能靠 live 评测的逐条明细人工看。把两者混进一个数字是最容易犯的错。
 *       {@code overRefusals}（该答的题被判成依据不足）的成因可能是门过严，
 *       也可能是检索压根没把该召回的文档召回来——单看数字分不出，要看逐条明细。</li>
 * </ul>
 * <p>
 * <b>四种输入，一套算法。</b>离线重放（{@code GroundingEvalRunner}，吃夹具里的真实输出）
 * 与线上真跑（ai-service 的 live 评测）都走 {@link #evaluate}，
 * 所以两组数字可以直接对比——差别只在数据来源，不在尺子。
 */
public final class GroundingEvaluator {

    /**
     * 一次评测的输入样本。
     * <p>
     * 与 {@link GroundingFixtures.Case} 分开是为了让 live 评测不必伪造夹具对象：
     * 它只需要凑齐这几个字段，其余（采集时间、耗时、运行时校验的报数）与判定无关。
     *
     * @param evidenceLevel 拒答门判定。<b>由评测方给出而非现场重算</b>：
     *                      离线重放用的是采集当时的判定（那正是"运行时"的判定），
     *                      live 评测用刚跑出来的判定。现场重算会引入
     *                      "用今天的阈值判昨天的输出"这种口径漂移。
     */
    public record Sample(String id, GroundingFixtures.Kind kind, boolean expectRefuse,
                         List<String> mustMention, List<DocumentChunk> evidence, String answer,
                         EvidenceGate.Level evidenceLevel, boolean toolEvidence) {
    }

    /**
     * 这条用例走了哪条判定路径 —— 报告页的逐条明细要显示它。
     * <p>
     * 不显示的话，「0 条未支撑」既可能是「逐句查过、全都干净」，也可能是「压根没判」，
     * 两件事在表格里长得一模一样。
     */
    public enum Mode {
        /** 逐句判定（答案里有有效引用） */
        PER_SENTENCE,
        /** 整篇无依据：没有引用也没有工具记录，全篇按无出处计 */
        WHOLE_UNGROUNDED,
        /** 不适用：拒答用例只看门判定，或工具轮次没有角标可标 */
        NOT_APPLICABLE
    }

    /** 句子级引用检查的四种结果 */
    public enum CitationVerdict {
        /** 句中的每个锚点都能在它引用的证据里逐字找到 */
        OK,
        /** 句中有锚点、也有引用，但引用的证据里找不到这个锚点——引错了地方 */
        WRONG_TARGET,
        /** 句中有锚点，却没有任何有效引用——"该引没引"，已计入幻觉率 */
        MISSING,
        /** 句中的锚点找不到可判定的引用（越界编号），单列为编造引用 */
        OUT_OF_RANGE
    }

    /**
     * @param sentence 被检查的句子原文
     * @param anchors  句中出现的锚点词
     * @param refs     该句可用的引用编号（1 基）：自身的有效引用，没有时是所在块的有效引用
     * @param verdict  判定结果
     */
    public record CitationCheck(String sentence, List<String> anchors, List<Integer> refs,
                                CitationVerdict verdict) {
    }

    /**
     * 一条用例的判定结果 —— 报告页逐条明细读的就是它。
     *
     * @param mode           走了哪条判定路径（见 {@link Mode}）
     * @param answerable     该用例是否属于"该答"的两类（可答 / 多跳）
     * @param factSentences  参与幻觉率分母的"事实句"数：含锚点、且不是引出句的句子；
     *                       整篇无依据时是全部成句数
     * @param unsupported    未支撑的事实句数（无引用 / 引用里找不到锚点 / 整篇无依据）
     * @param checks         参与引用准确率分母的句子（有锚点且有自身有效引用）
     * @param wrongCitations 其中引错了地方的句数
     * @param outOfRange     越界引用（[7] 而本轮只有 3 条证据）的出现次数——确定的编造
     * @param gateRefused    拒答门是否判成"拒答侧"（NONE / WEAK）
     * @param refusalCorrect 门判定与期望是否一致
     * @param multiHopHit    多跳：结论要点全部落到答案里，且引用了两篇以上文档
     */
    public record CaseOutcome(String id, GroundingFixtures.Kind kind, Mode mode, boolean answerable,
                              int sentences, int factSentences, int unsupported, int checks,
                              int wrongCitations, int outOfRange, boolean gateRefused,
                              boolean refusalCorrect, boolean multiHopHit,
                              List<CitationCheck> citations) {
    }

    /**
     * 四项指标。
     * <p>
     * 每项都带分母：比例单独出现时，读者无法判断它是"3 条全对"还是"300 条全对"，
     * 而这两件事的说服力差着一个量级。
     *
     * @param hallucinationRate   未支撑事实句 / 事实句总数。答案整篇无依据时，
     *                            该用例按全篇计——它一句都没出处
     * @param citationAccuracy    OK /（OK + WRONG_TARGET）。越界引用不进这个分母，
     *                            它们单独计数：那是"引了不存在的证据"，比引错更硬
     * @param citationChecks      引用准确率的分母（有锚点且有引用的句子数），
     *                            为 0 时该指标记 1.0 并在此处暴露 0，避免"没数据"被读成"全对"
     * @param refusalAccuracy     门判定与期望一致的比例（含"该拒未拒"与"不该拒却拒"两个方向）
     * @param missedRefusals      该拒未拒 —— 最危险的方向，单列
     * @param overRefusals        该答的题上判成依据不足 —— 可能门过严，也可能检索没召回，
     *                            要看逐条明细；单列
     * @param multiHopHitRate     多跳用例的命中率
     * @param multiHopCases       多跳用例总数
     */
    public record Metrics(int caseCount, double hallucinationRate, int factSentences,
                          int unsupportedSentences, double citationAccuracy, int citationChecks,
                          int wrongCitations, int outOfRangeCitations, double refusalAccuracy,
                          int missedRefusals, int overRefusals, double multiHopHitRate,
                          int multiHopCases) {
    }

    public record Report(Metrics metrics, List<CaseOutcome> cases) {
    }

    /** 正文里的引用角标，与 {@link CitationVerifier} 同一定义 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    /** 断句与 {@link CitationVerifier} 保持一致，否则"句数"两边对不上，指标没法互相解释 */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\n]+[。！？!?；;\n]*");

    /** 列表项行首（-、*、+ 或 1. / 1、 / 1) ）——块切分时列表要跨空行延续 */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.、)])\\s");

    /** "成句"阈值，与 {@link CitationVerifier#needsCitation} 同一个数：两处对一句话是不是断言的定义必须一致 */
    private static final int FACT_MIN_LENGTH = 12;

    private GroundingEvaluator() {
    }

    public static Report evaluate(List<Sample> samples) {
        List<CaseOutcome> outcomes = samples.stream().map(GroundingEvaluator::evaluateCase).toList();
        return new Report(aggregate(outcomes), outcomes);
    }

    private static CaseOutcome evaluateCase(Sample sample) {
        int evidenceCount = sample.evidence() == null ? 0 : sample.evidence().size();
        String answer = sample.answer() == null ? "" : sample.answer();
        CitationVerifier.Verdict verdict = CitationVerifier.verify(answer, evidenceCount, sample.toolEvidence());

        boolean answerable = sample.kind() != GroundingFixtures.Kind.UNANSWERABLE;
        boolean gateRefused = sample.evidenceLevel() != EvidenceGate.Level.SUFFICIENT;
        boolean refusalCorrect = gateRefused == sample.expectRefuse();
        int outOfRange = countOutOfRange(answer, evidenceCount);
        boolean multiHopHit = sample.kind() == GroundingFixtures.Kind.MULTI_HOP
                && anchorsMissingFromAnswer(sample).isEmpty()
                && distinctCitedDocs(sample, evidenceCount) >= 2;

        // 不该答的用例只看一件事：门判得对不对。它答了什么不进另外三项的账——
        // 那些账的分母是"该答的题"，把拒答的产出混进来会两头失真
        if (!answerable) {
            return new CaseOutcome(sample.id(), sample.kind(), Mode.NOT_APPLICABLE, false,
                    verdict.sentences(), 0, 0, 0, 0, outOfRange, gateRefused, refusalCorrect,
                    false, List.of());
        }

        List<Integer> answerRefs = validRefs(answer, evidenceCount);
        if (answerRefs.isEmpty()) {
            // 没有引用：区分「工具轮次」与「整篇无依据」——判据取自 CitationVerifier，
            // 两边对同一份回答给出同一个定性
            if (!verdict.ungrounded()) {
                return new CaseOutcome(sample.id(), sample.kind(), Mode.NOT_APPLICABLE, true,
                        verdict.sentences(), 0, 0, 0, 0, outOfRange, gateRefused, refusalCorrect,
                        multiHopHit, List.of());
            }
            int facts = longSentenceCount(answer);
            return new CaseOutcome(sample.id(), sample.kind(), Mode.WHOLE_UNGROUNDED, true,
                    verdict.sentences(), facts, facts, 0, 0, outOfRange, gateRefused, refusalCorrect,
                    multiHopHit, List.of());
        }

        // 逐句判定
        List<CitationCheck> checks = new ArrayList<>();
        int factSentences = 0;
        int unsupported = 0;
        // 引用准确率的分母只统计<b>自带角标</b>的句子——「引的对不对」问的是带引用的句子，
        // 靠块内角标兜底的句子没有自己的引用可评（它们仍进幻觉率，那是另一个问题）
        int accuracyChecks = 0;
        int accuracyWrong = 0;
        for (String block : blocksOf(answer)) {
            List<String> sentences = sentencesOf(block);
            Set<Integer> blockRefs = new LinkedHashSet<>();
            for (String sentence : sentences) {
                blockRefs.addAll(validRefs(sentence, evidenceCount));
            }
            for (String sentence : sentences) {
                List<String> anchors = anchorsIn(sentence, sample.mustMention());
                List<Integer> ownRefs = validRefs(sentence, evidenceCount);
                if (!anchors.isEmpty() && !ownRefs.isEmpty()) {
                    boolean covered = covered(sample, ownRefs, anchors);
                    checks.add(new CitationCheck(sentence.trim(), anchors, ownRefs,
                            covered ? CitationVerdict.OK : CitationVerdict.WRONG_TARGET));
                    accuracyChecks++;
                    if (!covered) {
                        accuracyWrong++;
                        unsupported++;
                    }
                }
                if (anchors.isEmpty() || isLeadIn(sentence)) {
                    continue;
                }
                factSentences++;
                if (ownRefs.isEmpty()) {
                    // 自身没角标：看同块（列表/整段常共用一个角标）
                    if (blockRefs.isEmpty()) {
                        unsupported++;
                        checks.add(new CitationCheck(sentence.trim(), anchors, List.of(),
                                outOfRangeIn(sentence, evidenceCount)
                                        ? CitationVerdict.OUT_OF_RANGE : CitationVerdict.MISSING));
                    } else if (!covered(sample, List.copyOf(blockRefs), anchors)) {
                        unsupported++;
                        checks.add(new CitationCheck(sentence.trim(), anchors, List.copyOf(blockRefs),
                                CitationVerdict.WRONG_TARGET));
                    }
                }
            }
        }

        return new CaseOutcome(sample.id(), sample.kind(), Mode.PER_SENTENCE, true,
                verdict.sentences(), factSentences, unsupported, accuracyChecks,
                accuracyWrong, outOfRange, gateRefused, refusalCorrect, multiHopHit,
                List.copyOf(checks));
    }

    /**
     * 由逐条判定汇总出四项指标。
     * <p>
     * 公开是因为 live 真跑是<b>边跑边判</b>的：每条用例一出结果就判完，跑完只剩一堆
     * {@link CaseOutcome}，再要求它把样本拼回来纯属多余——汇总本来就只读这些字段。
     */
    public static Metrics aggregate(List<CaseOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new Metrics(0, 1.0, 0, 0, 1.0, 0, 0, 0, 1.0, 0, 0, 1.0, 0);
        }
        int factSentences = outcomes.stream().mapToInt(CaseOutcome::factSentences).sum();
        int unsupported = outcomes.stream().mapToInt(CaseOutcome::unsupported).sum();
        int checks = outcomes.stream().mapToInt(CaseOutcome::checks).sum();
        int wrong = outcomes.stream().mapToInt(CaseOutcome::wrongCitations).sum();
        int outOfRange = outcomes.stream().mapToInt(CaseOutcome::outOfRange).sum();

        long missed = outcomes.stream().filter(o -> o.kind() == GroundingFixtures.Kind.UNANSWERABLE)
                .filter(o -> !o.gateRefused()).count();
        long over = outcomes.stream().filter(CaseOutcome::answerable).filter(CaseOutcome::gateRefused).count();
        long refusalCorrect = outcomes.stream().filter(CaseOutcome::refusalCorrect).count();

        List<CaseOutcome> multiHop = outcomes.stream()
                .filter(o -> o.kind() == GroundingFixtures.Kind.MULTI_HOP).toList();
        double multiHopRate = multiHop.isEmpty() ? 1.0
                : (double) multiHop.stream().filter(CaseOutcome::multiHopHit).count() / multiHop.size();

        return new Metrics(
                outcomes.size(),
                factSentences == 0 ? 1.0 : (double) unsupported / factSentences,
                factSentences, unsupported,
                // 没有可检查的引用时记 1.0（无引用可错），但 citationChecks=0 会一起下发，
                // 报告页据此显示"无样本"而不是"100% 准确"
                checks == 0 ? 1.0 : (double) (checks - wrong) / checks,
                checks, wrong, outOfRange,
                (double) refusalCorrect / outcomes.size(),
                (int) missed, (int) over,
                multiHopRate, multiHop.size());
    }

    // ==================== 判定细节 ====================

    /**
     * 块切分：空行是分隔，但<b>列表跨空行延续</b>（markdown 的松散列表与紧凑列表
     * 是同一个列表，中间恰好空一行不该把引用范围切断）。块是"无角标句子"的引用范围。
     */
    private static List<String> blocksOf(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inList = false;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                if (!inList && !current.isEmpty()) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            boolean item = LIST_ITEM.matcher(line).find();
            if (!current.isEmpty() && inList && !item) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
            inList = item;
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private static List<String> sentencesOf(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            sentences.add(matcher.group());
        }
        return sentences;
    }

    /** 引出句：「……规则如下：」在引出下文，不是在断言什么 */
    private static boolean isLeadIn(String sentence) {
        String trimmed = sentence.trim();
        return trimmed.endsWith("：") || trimmed.endsWith(":");
    }

    /** 整篇无依据时的事实句数：每个成句都是一句无出处的断言 */
    private static int longSentenceCount(String text) {
        return (int) sentencesOf(text).stream()
                .filter(s -> !s.isBlank())
                .filter(s -> s.trim().codePointCount(0, s.trim().length()) >= FACT_MIN_LENGTH)
                .count();
    }

    /** 句中的锚点词。返回顺序按夹具里的顺序，报告页读起来与标注一致 */
    private static List<String> anchorsIn(String sentence, List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        String normalized = normalize(sentence);
        return anchors.stream().filter(a -> normalized.contains(normalize(a))).toList();
    }

    /** 每个锚点都能在引用的某条证据里逐字找到 */
    private static boolean covered(Sample sample, List<Integer> refs, List<String> anchors) {
        return anchors.stream().allMatch(anchor ->
                refs.stream().anyMatch(ref -> evidenceContains(sample, ref, anchor)));
    }

    /** 文本中落在证据条数之内的引用编号，去重保序 */
    private static List<Integer> validRefs(String text, int evidenceCount) {
        Set<Integer> refs = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            int no = Integer.parseInt(matcher.group(1));
            if (no >= 1 && no <= evidenceCount) {
                refs.add(no);
            }
        }
        return List.copyOf(refs);
    }

    private static int countOutOfRange(String text, int evidenceCount) {
        int count = 0;
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            int no = Integer.parseInt(matcher.group(1));
            if (no < 1 || no > evidenceCount) {
                count++;
            }
        }
        return count;
    }

    private static boolean outOfRangeIn(String sentence, int evidenceCount) {
        return countOutOfRange(sentence, evidenceCount) > 0;
    }

    private static boolean evidenceContains(Sample sample, int ref, String anchor) {
        if (sample.evidence() == null || ref < 1 || ref > sample.evidence().size()) {
            return false;
        }
        DocumentChunk chunk = sample.evidence().get(ref - 1);
        if (chunk == null || chunk.getContent() == null) {
            return false;
        }
        return normalize(chunk.getContent()).contains(normalize(anchor));
    }

    /** 答案里漏掉的锚点 —— 多跳命中率要求它为空 */
    private static List<String> anchorsMissingFromAnswer(Sample sample) {
        if (sample.mustMention() == null || sample.mustMention().isEmpty()) {
            return List.of();
        }
        String answer = normalize(sample.answer() == null ? "" : sample.answer());
        return sample.mustMention().stream().filter(a -> !answer.contains(normalize(a))).toList();
    }

    /** 答案实际引用到的不同文档数 —— 多跳的"跨文档"这一半判据 */
    private static int distinctCitedDocs(Sample sample, int evidenceCount) {
        Set<String> docs = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(sample.answer() == null ? "" : sample.answer());
        while (matcher.find()) {
            int no = Integer.parseInt(matcher.group(1));
            if (no >= 1 && no <= evidenceCount && sample.evidence().get(no - 1) != null) {
                docs.add(String.valueOf(sample.evidence().get(no - 1).getDocId()));
            }
        }
        return docs.size();
    }

    /**
     * 比对前的归一化：<b>只处理空白与全半角</b>。
     * <p>
     * 文档写 {@code 2000IU}、模型写 {@code 2000 IU}，这是排版差异不是事实差异，
     * 不该判成"引用错"。再往外走（同义词、单位换算、数字等值）就需要语义判断了——
     * 那正是本项目刻意不做的部分，边界写在类注释里。
     */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 全角数字/字母 → 半角；全角标点不动（它参与断句，改动会改变句界）
            if (c >= '！' && c <= '～') {
                char half = (char) (c - 0xFEE0);
                sb.append(Character.isLetterOrDigit(half) ? half : c);
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 报告页按用例 id 排；这里也按 id 排，保证两次运行的行顺序一致 */
    public static Comparator<CaseOutcome> byId() {
        return Comparator.comparing(CaseOutcome::id);
    }
}
