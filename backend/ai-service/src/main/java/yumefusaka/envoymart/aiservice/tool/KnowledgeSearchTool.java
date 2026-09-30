package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.KnowledgePrompt;
import yumefusaka.envoymart.agent.rag.RAGEngine;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 知识库再检索 —— 让 ReAct 循环能在推理途中重新检索一次，而不是只有开头那一次机会。
 * <p>
 * <b>补的是什么：</b>系统提示里的「知识依据」是入口处一次检索的产物，检索句是用户原话
 * （已做指代消解，见 {@code QueryRewriter}）。但模型执行到中途才会发现真正要查什么——
 * 先查订单知道买家买的是鱼油，才想起该查「鱼油与华法林的相互作用」；一轮 ReAct 里
 * 信息是逐步长出来的，而检索机会只在起点发了一次。多跳问题的后半跳因此无人问津。
 * <p>
 * <b>出处形式与编号证据刻意分开：</b>工具返回的片段不在 system prompt 里，{@code [n]}
 * 编号空间只属于 prompt 证据——让模型给工具片段标编号，轻则越界编号被第三道闸摘除，
 * 重则逼它编一个不存在的编号。所以这里要求以《文档名》在句末标出处，
 * 而 {@code CitationVerifier} 按「平台声明过的标题」识别这种写法（见该类的
 * {@code citableTitles}）——输出里每条的「出处：」行就是标题被采信的凭据。
 */
@Slf4j
public class KnowledgeSearchTool implements Tool {

    /** 默认返回几条。与入口检索的 ragTopK 同量级：片段越多，模型越容易拿边角料当结论 */
    private static final int DEFAULT_TOP_K = 3;
    /** 一次最多几条。上限不是防御向量库，是防御模型把「查得更全」理解成「一次多查」 */
    private static final int MAX_TOP_K = 5;
    /**
     * 检索词长度上限。检索句是短句，超过它基本是模型把整段问题塞了进来——
     * 那种输入会命中一片碎片，看似「查到了」，实则稀释掉真正相关的片段。
     */
    private static final int MAX_QUERY_CHARS = 120;
    /** 单条片段在给模型看的文本里截断到这个长度；完整内容不截（rawData 随响应下发） */
    private static final int QUOTE_CHARS = 400;

    private final RAGEngine ragEngine;

    public KnowledgeSearchTool(RAGEngine ragEngine) {
        this.ragEngine = ragEngine;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(KnowledgePrompt.SEARCH_TOOL_NAME)
                .description("检索平台知识库原文（平台规则、政策条款、商品说明书、成分与用法、人群禁忌等），"
                        + "返回带《文档名》与章节位置的原文片段。"
                        + "当系统提示中的知识依据不足以回答、或需要在推理中途针对某个具体方面再查一次时使用。"
                        + "注意：入口已自动检索过一次用户原话，不要用它重复查询同一个问题。")
                .parameters(Map.of(
                        "query", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("检索词或短句，写具体名词组合（如「维生素D 每日摄入上限」「七天无理由 退货条件」），"
                                        + "不要写整段问题，也不要写「请问」「帮我查」这类问句外壳")
                                .required(true).build(),
                        "limit", ToolDefinition.ParameterSpec.builder()
                                .type("integer")
                                .description("返回条数，默认 3，最多 5")
                                .required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = str(call.getArguments().get("query"));
        if (query == null || query.isBlank()) {
            return ToolResult.builder().success(false)
                    .errorMessage("缺少参数 query：需要一句检索词").build();
        }
        if (query.length() > MAX_QUERY_CHARS) {
            return ToolResult.builder().success(false)
                    .errorMessage("query 过长（" + query.length() + " 字，上限 " + MAX_QUERY_CHARS
                            + "）：请压缩成名词组合，如「维生素D 每日上限」").build();
        }
        int topK = clampTopK(call.getArguments().get("limit"));

        try {
            List<DocumentChunk> chunks = ragEngine.retrieve(query, topK);
            if (chunks == null || chunks.isEmpty()) {
                // 「跑通了但没查到」是第三种结局：换个查询词可能就有，不能报成失败，
                // 也不能让模型把「没检索到」念成「知识库说了没有」
                return ToolResult.builder().success(true).noData(true)
                        .output(emptyText(query))
                        .build();
            }
            return ToolResult.builder().success(true)
                    .output(render(query, chunks))
                    .rawData(chunks)
                    .build();
        } catch (Exception e) {
            log.error("[KnowledgeSearch] 检索失败 query={}", query, e);
            // 失败必须让模型知道「这次没查成」，否则它会顺着上文把没查到的部分补全
            return Downstream.failure("知识库检索", e, "知识库没有相关内容");
        }
    }

    // ==================== 入参 ====================

    static int clampTopK(Object raw) {
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw != null) {
            try {
                value = Integer.parseInt(String.valueOf(raw).strip());
            } catch (NumberFormatException ignored) {
                // 模型偶尔把 limit 写成一句话，按默认值处理
            }
        }
        if (value == null) {
            return DEFAULT_TOP_K;
        }
        return Math.max(1, Math.min(MAX_TOP_K, value));
    }

    // ==================== 渲染 ====================

    private String emptyText(String query) {
        return """
                知识库中没有检索到与「%s」相关的片段。

                处理要求：
                - 不要用你自己的知识补全这个问题；涉及平台规则、商品参数时如实说明知识库中没有查到依据。
                - 可以换更具体的名词再检索一次（如把「这个能不能吃」换成具体成分名）。
                """.formatted(query);
    }

    private String render(String query, List<DocumentChunk> chunks) {
        StringBuilder sb = new StringBuilder("知识库检索结果（查询：")
                .append(query).append("，命中 ").append(chunks.size()).append(" 条）\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('\n');
            appendEntry(sb, i + 1, chunks.get(i));
        }
        String example = chunks.get(0).getTitle() == null ? "文档名" : chunks.get(0).getTitle();
        return sb.append("""

                回答要求：
                - 以上片段是平台知识库原文，可作为依据；引用它们时在句末以《文档名》标注出处，例如「……（《%s》）」。
                - 不要给这些片段标注 [编号] 角标——编号空间只属于系统提示中的证据，混用会被引用校验当作编造编号。
                - 反过来也一样：**系统提示里的条目仍然标 [编号]，不要一并改写成《文档名》**。
                  两套写法按来源分、不按文档分——编号是界面上点回原文的入口。
                - 片段没有覆盖的内容不要用常识补全，如实说这部分知识库没有查到。
                - 涉及处方药或已确诊疾病的，提醒用户遵医嘱，不要给出「可以吃/不能吃」的处方级结论。
                """.formatted(example)).toString();
    }

    /** 标题行必须带「出处：」——引用校验按这一行采信可引用标题，见类注释 */
    private void appendEntry(StringBuilder sb, int no, DocumentChunk chunk) {
        sb.append("[片段 ").append(no).append("] 出处：").append(locate(chunk));
        if (notBlank(chunk.getSource())) {
            sb.append(" ｜ 来源：").append(chunk.getSource());
        }
        if (notBlank(chunk.getVersion())) {
            sb.append(" ｜ 版本：").append(chunk.getVersion());
        }
        sb.append('\n').append(truncate(chunk.getContent())).append('\n');
    }

    /**
     * 与 {@code KnowledgePrompt} 的条目定位同一套优先级：位置串（切分时已带上标题）
     * → 标题 → docId。<b>位置串直接用，标题缺书名号时补上</b>——不带书名号，
     * 引用校验就采信不到这个标题。
     */
    private String locate(DocumentChunk chunk) {
        if (notBlank(chunk.getPosition())) {
            return chunk.getPosition();
        }
        if (notBlank(chunk.getTitle())) {
            return "《" + chunk.getTitle() + "》";
        }
        return String.valueOf(chunk.getDocId());
    }

    private String truncate(String content) {
        if (content == null) {
            return "（原文缺失）";
        }
        String stripped = content.strip();
        return stripped.length() <= QUOTE_CHARS ? stripped : stripped.substring(0, QUOTE_CHARS) + "…";
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw).strip();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
