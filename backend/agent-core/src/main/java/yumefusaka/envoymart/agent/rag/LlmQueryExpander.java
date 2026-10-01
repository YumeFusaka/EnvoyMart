package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于模型的查询扩写 —— 一次调用同时产出 HyDE 假想答案与多角度改写，只输出 JSON。
 * <p>
 * <b>为什么两样产出挤在同一次调用里：</b>它们读的是同一个输入、共享同一段提示词与
 * 同一次上下文装载。分成两次调用，等于把同一句话交给模型两次，还要付两遍延迟——
 * 而这两样产出之间没有任何依赖，没有理由串行。
 * <p>
 * <b>假想答案是会编的，所以它只喂给向量路。</b>模型写出来的那段「资料原文」可能含
 * 编造的数字与条件；它唯一的作用是<b>把查询推向文档的措辞分布</b>，让向量空间里的距离
 * 变近。它<b>从不进入回答侧</b>——回答侧的证据永远是检索回来的真实切片，
 * 这一点由链路结构保证（{@link MultiQueryRetriever} 只把结果交给检索）。
 * <p>
 * <b>角度改写为什么不能省：</b>词法路（BM25）只认字面重合。用户说「东西还没到」，
 * 文档写「配送时效」，两者一个词都对不上——换个说法重问一次，就有句子能对上。
 * 它与假想答案补的不是同一个洞：一个补词汇差距，一个补表述差距。
 * <p>
 * <b>降级策略与 {@code QueryRewriter} 同源，但清理方式不同：</b>
 * <ul>
 *   <li>改写器的产出要下发给用户看，所以「太长」意味着它没在改写而在解释——整条丢弃；</li>
 *   <li>假想答案从不示人、只用于向量化，太长说明它写嗨了，<b>截断</b>即可——
 *      一段被截断的同主题文字，在向量空间里仍然落在该落的地方，截断不引入错误信息。</li>
 * </ul>
 */
@Slf4j
public class LlmQueryExpander implements QueryExpander {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 假想答案的字数上限。HyDE 的收益来自「像文档」，不来自「写得长」 */
    private static final int MAX_HYPOTHETICAL_CHARS = 300;
    /** 角度改写条数上限。多一条就多一遍 BM25，而收益随条数迅速递减 */
    private static final int MAX_ANGLES = 3;
    /** 单条角度改写的字数上限：一句查询该有的长度，超了说明它在写解释 */
    private static final int MAX_ANGLE_CHARS = 60;
    /** 输出上限：150 字假想答案 + 2 条角度约 300 token，留一倍余量 */
    private static final int EXPAND_MAX_TOKENS = 700;

    /**
     * 知识库的领域说明。<b>写死在提示词里而不是配置项</b>：它描述的是这套知识库
     * 用什么口吻说话（售后政策、物流时效、成分与用法），换个领域时提示词本身
     * 就该跟着重写——把它做成可配置的字符串，只会让人以为改个配置就能换个领域。
     */
    private static final String DOMAIN = "电商平台的售后服务与营养保健知识库";

    private final LLMProvider llm;
    private final LLMConfig llmConfig;

    public LlmQueryExpander(LLMProvider llm, LLMConfig llmConfig) {
        this.llm = llm;
        this.llmConfig = llmConfig;
    }

    @Override
    public QueryExpansions expand(String query) {
        if (query == null || query.isBlank() || !llm.supportsReasoning()) {
            return QueryExpansions.none();
        }
        try {
            LLMResponse response = llm.chat(buildPrompt(query), expandConfig());
            QueryExpansions expansions = parse(response == null ? null : response.getContent());
            if (expansions.isEmpty()) {
                // 「扩写了但扩出个空」与「没扩写」结果一样、原因不同：前者是模型判断不需要扩，
                // 后者是模型没答上来。区分开，才能判断这条链路到底有没有在工作
                log.info("[QueryExpand] 「{}」未产出可用扩写", query);
            } else {
                log.info("[QueryExpand] 「{}」→ 假想 {} 字 + 角度 {} 条",
                        query, expansions.hypothetical() == null ? 0 : expansions.hypothetical().length(),
                        expansions.angles().size());
            }
            return expansions;
        } catch (Exception e) {
            log.warn("[QueryExpand] 扩写失败，退回原句检索：{}", e.toString());
            return QueryExpansions.none();
        }
    }

    private List<ChatMessage> buildPrompt(String query) {
        return List.of(
                ChatMessage.builder().role(ChatMessage.Role.SYSTEM).content("""
                        你是检索查询扩写器，为一个【%s】做检索前的查询扩写。
                        给定一条查询，产出两样东西，只输出 JSON，不要任何解释、不要代码块围栏：

                        1. "hypothetical"：一段假想的资料原文 —— 假设这个知识库里恰好有一段能回答
                           该查询的文字，把它写出来。要求：
                           - 用资料的口吻（陈述句、说明书或政策条款的写法），使用该领域文档会用的词
                           - 可以含具体的名词、成分名、数字与条件
                           - 不要编造订单号、具体日期、品牌承诺或医疗诊断结论
                           - 不要出现"根据资料""我不确定""建议咨询客服"这类对话口气
                           - 150 字以内

                        2. "angles"：2 条改写，用不同的说法问同一件事，覆盖用户可能用的其他词汇
                           （同义词、上位词、口语说法、专业术语）。每条 30 字以内。

                        用户的问题常常很短、很口语，主语宾语都省了（"太贵了""着急用""催一下进度"）。
                        这类查询恰恰最需要扩写 —— 把省掉的那件事补出来，把口语换成知识库会用的词。
                        两样产出每一条都必须给出，不要因为「问题已经很清楚」就留空：
                        扩写的成本是固定的一次调用，留空等于这次调用白花，
                        而"要不要扩"这个判断交给模型时，实测它会放过大半真正该扩的短查询。

                        输出形如：{"hypothetical":"...","angles":["...","..."]}
                        """.formatted(DOMAIN)).build(),
                ChatMessage.builder().role(ChatMessage.Role.USER).content(query).build());
    }

    /** 扩写是确定性任务：温度归零；模型/地址等连接参数沿用主配置 */
    private LLMConfig expandConfig() {
        return LLMConfig.builder()
                .model(llmConfig.getModel())
                .baseUrl(llmConfig.getBaseUrl())
                .apiKey(llmConfig.getApiKey())
                .temperature(0)
                .maxTokens(EXPAND_MAX_TOKENS)
                .build();
    }

    /**
     * 解析模型输出。<b>任何一处不合规都只丢弃不合规的那一项，不整体作废</b>——
     * 假想答案跑偏了，那两个角度改写可能还是好的，没有理由一起扔掉。
     * 抠 {@code {} 而不是直接反序列化整段：模型十次里有几次会带上围栏或一句开场白。
     */
    private QueryExpansions parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return QueryExpansions.none();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return QueryExpansions.none();
        }

        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(raw.substring(start, end + 1), new TypeReference<>() {
            });
        } catch (Exception e) {
            // 解析失败与外层 catch 的失败要分开记：前者是「模型没按格式答」，
            // 后者是「调用根本没成」，两者的处置动作完全不同
            log.warn("[QueryExpand] 模型输出不是合法 JSON，本次不扩写：{}", e.getMessage());
            return QueryExpansions.none();
        }

        String hypothetical = sanitizeText(parsed.get("hypothetical"), MAX_HYPOTHETICAL_CHARS);
        List<String> angles = sanitizeAngles(parsed.get("angles"));
        return new QueryExpansions(hypothetical, angles);
    }

    /** 单段文本：非字符串或空白视为没有；超长截断（理由见类注释） */
    private static String sanitizeText(Object raw, int maxChars) {
        if (!(raw instanceof String s)) {
            return null;
        }
        String trimmed = s.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        // 截断点落在代理对中间会留下一个孤立的高代理项，送进 embedding 就是一段非法文本
        int cut = Character.isHighSurrogate(trimmed.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return trimmed.substring(0, cut);
    }

    /** 角度列表：逐条清洗，超长的那条丢弃（它已经不是「一句查询」了） */
    private static List<String> sanitizeAngles(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> angles = new ArrayList<>(Math.min(list.size(), MAX_ANGLES));
        for (Object item : list) {
            if (angles.size() >= MAX_ANGLES) {
                break;
            }
            if (item instanceof String s && !s.isBlank() && s.strip().length() <= MAX_ANGLE_CHARS) {
                angles.add(s.strip());
            }
        }
        return angles;
    }
}
