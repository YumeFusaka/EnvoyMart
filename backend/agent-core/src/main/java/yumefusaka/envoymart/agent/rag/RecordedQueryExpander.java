package yumefusaka.envoymart.agent.rag;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 预录扩写器 —— 读真实模型输出的快照，让扩写这件事在<b>无 Key、无网络、可重复</b>的
 * 条件下被测到。与 {@code GroundingFixtures} 是同一套办法、同一个已知代价。
 * <p>
 * <b>它替换掉的是一次真实模型调用，不是扩写这件事。</b>线上用的始终是
 * {@link LlmQueryExpander}；这个类的使命是让 CI 门禁与效果对照实验能对同一批查询
 * 反复跑出同一个数字。夹具里每条记录都是<b>某一时刻某个模型的真实输出</b>，
 * 采集时间与模型标识与数字一起展示——不许把它讲成"现在的表现"。
 * <p>
 * <b>查不到就返回"没有扩写"，不抛异常。</b>夹具与评测样本会各自演进：样本里新加一条，
 * 夹具就要重新采集，而"重采之前那条样本先按原句检索"是比"整个评测跑不起来"更有用的行为——
 * 它让数字只是保守（偏低），而不是让门禁消失。门禁那边另有一条覆盖度断言，
 * 免得"保守"悄悄变成"没测"。
 */
public final class RecordedQueryExpander implements QueryExpander {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 采集时间，与数字一起展示（"这份数字是什么时候的"） */
    public static final String CAPTURED_AT;

    /** 采集时的模型标识，同上 */
    public static final String MODEL;

    private static final Map<String, QueryExpansions> BY_QUERY;

    static {
        Map<String, Object> raw = load();
        CAPTURED_AT = String.valueOf(raw.getOrDefault("capturedAt", ""));
        MODEL = String.valueOf(raw.getOrDefault("model", ""));

        List<?> cases = (List<?>) raw.getOrDefault("cases", List.of());
        Map<String, QueryExpansions> byQuery = new java.util.LinkedHashMap<>();
        for (Object item : cases) {
            Map<?, ?> record = (Map<?, ?>) item;
            String query = record.get("query") instanceof String s ? s : null;
            if (query == null) {
                continue;
            }
            byQuery.put(query, new QueryExpansions(
                    record.get("hypothetical") instanceof String h ? h : null,
                    record.get("angles") instanceof List<?> angles
                            ? angles.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                            : List.of()));
        }
        BY_QUERY = Map.copyOf(byQuery);
    }

    @Override
    public QueryExpansions expand(String query) {
        if (query == null) {
            return QueryExpansions.none();
        }
        return BY_QUERY.getOrDefault(query, QueryExpansions.none());
    }

    /** 夹具覆盖的查询条数 —— 覆盖度断言与报告页都要用 */
    public static int recordedCount() {
        return BY_QUERY.size();
    }

    /** 这条查询在夹具里有没有记录。缺的那条不是"无需扩写"，是"还没采到" */
    public static boolean covers(String query) {
        return query != null && BY_QUERY.containsKey(query);
    }

    private static Map<String, Object> load() {
        try (InputStream in = RecordedQueryExpander.class.getResourceAsStream("/eval/query-expansions.json")) {
            if (in == null) {
                // 与 GroundingFixtures 同一条原则：夹具缺失要当场炸，不能安静地退化成"没扩写"——
                // 那会让门禁变成一条恒真的断言，比没有门禁更糟
                throw new IllegalStateException("缺少 resources/eval/query-expansions.json，"
                        + "用 ExpansionCaptureTest 采集（需要 LLM_API_KEY）");
            }
            return MAPPER.readValue(in, new TypeReference<>() {
            });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("扩写夹具解析失败：" + e.getMessage(), e);
        }
    }
}
