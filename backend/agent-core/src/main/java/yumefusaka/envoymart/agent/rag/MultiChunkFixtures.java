package yumefusaka.envoymart.agent.rag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 多切片评测夹具 —— 语料与线上知识库<b>同形态</b>（U14）。
 * <p>
 * <b>为什么需要第二套夹具。</b>原有的 {@link EvalFixtures} 是 90 篇短文，每篇 30~51 字，
 * 短于切分窗口 {@code chunkSize}，于是<b>每篇恰好一片</b>——切片级融合、切分散列、
 * 跨片召回这些只在线上升效的行为，在那套语料上<b>结构性地测不出来</b>：
 * 换任何切分策略、任何融合粒度，指标都一模一样。
 * <p>
 * 这一套直接从线上 47 篇语料里取 9 篇原文（每篇 4~6 章、600~3200 字），
 * 加上 22 条标注查询。<b>两套夹具并存、各有其用</b>：
 * <ul>
 *   <li>{@link EvalFixtures}：90 篇短文，三档分层各 40 条 —— 回归基线，跨版本可比；</li>
 *   <li>本夹具：多切片长文档 —— 度量「切分与切片级融合到底有没有生效」。</li>
 * </ul>
 * <p>
 * <b>标注锚点是「条款文本」而不是 chunkId。</b>chunkId 形如 {@code KB-0001_3}，
 * 序号由切分参数决定——改一次 {@code chunkSize}，所有 chunkId 全部重排，
 * 标注整体作废。条款文本是文档自身的内容，切分怎么变它都在。
 * 命中判据因此是「召回的切片里有没有一条包含该条款」，与切分粒度解耦。
 * <p>
 * <b>{@code expectRefuse} 的样本在语料里没有对应条款</b>，正确行为是检索不到。
 * 它们不是「难检索」，而是「该拒」——与 {@code GroundingFixtures} 的 UNANSWERABLE 同一用意：
 * 把「该拒却答了」与「该答没答上」混在一张表里算总分，等于用回答率奖赏编造。
 */
public final class MultiChunkFixtures {

    /** 一条标注：查询 + 期望命中的条款文本（{@code expectRefuse} 时为 null）。 */
    public record Case(String query, String docId, String clause, boolean expectRefuse,
                       Stratum stratum) {
    }

    /** 分层 —— 与 {@link EvalFixtures} 同名同义：查询与文档用词的远近程度。 */
    public enum Stratum {
        /** 字面重合：查询与条款用词高度一致 */
        TEXTUAL,
        /** 口语改写：用户换个说法问同一件事 */
        PARAPHRASE,
        /** 语义鸿沟：词汇与语义都远，关键词路的天然短板 */
        HARD,
        /** 库里没有，正确行为是检索不到 */
        REFUSE
    }

    public static final List<Document> DOCS;
    public static final List<Case> CASES;

    static {
        Map<String, Object> raw = load();
        DOCS = ((List<?>) raw.get("documents")).stream().map(MultiChunkFixtures::toDocument).toList();
        CASES = ((List<?>) raw.get("cases")).stream().map(MultiChunkFixtures::toCase).toList();
    }

    private MultiChunkFixtures() {
    }

    public static List<Case> casesOf(Stratum stratum) {
        return CASES.stream().filter(c -> c.stratum() == stratum).toList();
    }

    /**
     * 与线上同一套切分参数 —— <b>直接复用 {@link StructuralSplitter#standard()}</b>。
     * <p>
     * 早先这里从夹具 JSON 里读 {@code chunkSize}/{@code chunkOverlap} 自己 new 一个，
     * 结果是「切分参数有两处定义」，正是 {@code KnowledgeCorpusTest} 那条守卫要拦的形态：
     * 线上调参时夹具不会跟着动，评测与生产悄悄分叉，而两边日志都正常。
     */
    public static StructuralSplitter splitter() {
        return StructuralSplitter.standard();
    }

    /** 把语料切成一整份切片列表，模拟线上「先切分再建索引」的那一步 */
    public static List<DocumentChunk> chunks() {
        TextSplitter splitter = splitter();
        return DOCS.stream().flatMap(doc -> splitter.split(doc).stream()).toList();
    }

    private static Map<String, Object> load() {
        try (InputStream in = MultiChunkFixtures.class
                .getResourceAsStream("/eval/multichunk-fixtures.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少评测夹具资源 /eval/multichunk-fixtures.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("多切片评测夹具读取失败", e);
        }
    }

    private static Document toDocument(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        return Document.builder()
                .id((String) map.get("id"))
                .title((String) map.get("title"))
                .content((String) map.get("content"))
                .source((String) map.get("source"))
                .scope((String) map.get("scope"))
                .version((String) map.get("version"))
                .tags(((List<?>) map.get("tags")).stream().map(String::valueOf).toList())
                .build();
    }

    private static Case toCase(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        return new Case((String) map.get("query"),
                (String) map.get("docId"),
                (String) map.get("clause"),
                Boolean.TRUE.equals(map.get("expectRefuse")),
                Stratum.valueOf((String) map.get("stratum")));
    }

    /**
     * 命中比较前的归一：忽略换行、缩进与空格，只比内容。
     * <p>
     * 条款文本在文档里可能跨行排版，而切片又会带上位置前缀；不归一就会把
     * 「同一句话，排版不同」判成没命中——那是把排版差异算进了召回率。
     * 口径与 {@code ChunkingQualityTest} / {@code LongDocFixtures} 完全一致。
     */
    public static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
