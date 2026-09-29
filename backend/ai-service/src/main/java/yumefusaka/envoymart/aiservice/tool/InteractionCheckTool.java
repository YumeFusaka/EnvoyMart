package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.graph.EntityKind;
import yumefusaka.envoymart.agent.graph.GraphRelation;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.KnowledgeClient;
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.InteractionReport;
import yumefusaka.envoymart.contract.Substance;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相互作用检查 —— 「这几样能不能一起用」。
 * <p>
 * <b>这个工具回答的是文本检索答不了的那类问题。</b>语料里写着「深海鱼油与华法林合用
 * 可能增加出血风险」，而用户问的是「我买的那瓶鱼油和华法林冲突吗」——两句话没有一个
 * 共同的实体名，BM25 与向量都接不上。图谱走的是 商品 → 成分 → 营养素 这条边，
 * 实体对得上就查得到，对不上就没有。
 * <p>
 * <b>输出里最要紧的不是风险条目，是「没查到」和「没收录」的分开表述。</b>
 * 这两种情况在界面上都表现为「没有风险条目」，而对一个药品营养场景来说，
 * 它们是相反的两句话：前者是「查过了，没发现」，后者是「系统里根本没这个东西」。
 * 一旦模型把后者念成前者，用户就会以为可以放心同服。因此这里对三种状态
 * （{@code 不可用 / 未收录 / 已收录}）各给一段不同的措辞和不同的指令。
 */
@Slf4j
public class InteractionCheckTool implements Tool {

    /**
     * 一次最多查几样。
     * <p>
     * 上限不是防御图谱（那边有超时兜底），是防御<b>模型把整段用户输入当参数塞进来</b>：
     * 图上的物质展开是变长路径，输入越多展开集越大。真实问法里超过五六样的
     * 「这些能不能一起吃」几乎不存在，真出现了也该拆成几轮问。
     */
    private static final int MAX_ITEMS = 6;
    /** 单个标识的长度上限。超过这个长度的不可能是实体名，只会是模型塞进来的一句话 */
    private static final int MAX_ITEM_CHARS = 30;
    /** 每条引文在<b>给模型看的文本</b>里截断到这个长度；完整引文在 rawData 里给前端 */
    private static final int QUOTE_CHARS = 150;

    private final KnowledgeClient knowledgeClient;

    public InteractionCheckTool(KnowledgeClient knowledgeClient) {
        this.knowledgeClient = knowledgeClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("interaction_check")
                .description("检查几样东西能不能同时使用或食用：商品、药物、成分、营养素之间的已知相互作用"
                        + "与人群禁忌。结论来自平台知识图谱，每条都附知识库原文出处。"
                        + "用户问「XX 和 YY 能一起吃吗」「我在吃华法林，能吃这个吗」「孕妇能吃吗」时使用。"
                        + "商品要传编号（如 SPU5）或完整商品名——不完整或只说品类时先调 product_search 拿到准确名称。")
                .parameters(Map.of(
                        "items", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("要一起检查的东西，逗号分隔，最多 6 样。"
                                        + "商品用编号（SPU5）或完整商品名，药物/成分/营养素用中文名。"
                                        + "例如「SPU5,华法林」或「鱼油软胶囊,阿司匹林」")
                                .required(true).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        List<String> items = parseItems(call.getArguments().get("items"));
        if (items.isEmpty()) {
            return ToolResult.builder().success(false)
                    .errorMessage("缺少参数 items：需要逗号分隔的实体名或商品编号").build();
        }

        try {
            InteractionReport report = knowledgeClient.interactions(String.join(",", items)).getData();
            if (report == null) {
                // 契约反序列化失败之类的静默故障。绝不能顺着往下渲染成「没有风险」
                return ToolResult.builder().success(false)
                        .errorMessage("知识图谱返回了空的检查结果，无法判断").build();
            }
            if (!report.available()) {
                return ToolResult.builder().success(true)
                        .output(unavailableText(report, items))
                        .rawData(report)
                        .build();
            }
            return ToolResult.builder().success(true)
                    .output(render(report, items))
                    .rawData(report)
                    .build();
        } catch (Exception e) {
            log.error("[InteractionCheck] 检查失败 items={}", items, e);
            // 失败也要让模型知道**这次没查成**。回一句 errorMessage 之后模型可能仍然
            // 顺着上文编一个结论，所以文案里直接把「不要说没有冲突」写进去
            return ToolResult.builder().success(false)
                    .errorMessage("相互作用检查未能完成（" + e.getMessage() + "）。"
                            + "不要据此说「没有冲突」，请告知用户暂时查不了。")
                    .build();
        }
    }

    // ==================== 入参 ====================

    /**
     * 解析 items。模型可能给字符串也可能给数组，两种都收。
     * <p>
     * 分隔符除了英文逗号还认全角逗号、顿号与分号：中文提问里「鱼油、华法林」是
     * 最常见的写法，只按英文逗号切的话整串会被当成一个实体，查出来是「没有收录」——
     * 一个由分隔符导致的假阴性，而它在日志上完全看不出来。
     */
    static List<String> parseItems(Object raw) {
        Collection<?> parts;
        if (raw instanceof Collection<?> collection) {
            parts = collection;
        } else if (raw == null) {
            return List.of();
        } else {
            parts = List.of(String.valueOf(raw).split("[,，、;；]"));
        }

        Set<String> items = new LinkedHashSet<>();
        for (Object part : parts) {
            String item = part == null ? "" : String.valueOf(part).strip();
            if (item.isEmpty() || item.length() > MAX_ITEM_CHARS) {
                continue;
            }
            items.add(item);
            if (items.size() >= MAX_ITEMS) {
                break;
            }
        }
        return List.copyOf(items);
    }

    // ==================== 渲染 ====================

    private String unavailableText(InteractionReport report, List<String> items) {
        return """
                知识图谱当前不可用，**这一次没有查成**（%s）。
                要检查的是：%s

                处理要求：
                - 不要说「没有冲突」「可以一起用」——你没有任何依据。
                - 如实告诉用户知识图谱暂时查不了，建议稍后重试，或查看商品页标注与说明书。
                """.formatted(nullSafe(report.note()), String.join("、", items));
    }

    private String render(InteractionReport report, List<String> items) {
        StringBuilder sb = new StringBuilder("平台知识图谱检查结果（范围：")
                .append(String.join("、", items)).append("）\n");

        boolean anyRisk = false;
        boolean anyMissing = false;
        for (InteractionReport.Item item : report.items()) {
            if (!item.found()) {
                anyMissing = true;
                sb.append("\n【").append(item.input()).append("】图谱中没有收录\n")
                        .append("  没有收录不等于安全，只是平台查不到这样东西。\n");
                continue;
            }
            sb.append("\n【").append(item.label()).append("】已收录\n");

            String substances = substanceLine(item);
            if (!substances.isEmpty()) {
                sb.append("  图谱展开出的成分与营养素：").append(substances).append("\n");
            }
            if (item.risks().isEmpty()) {
                sb.append("  未发现已知的相互作用或人群禁忌。\n");
                continue;
            }
            anyRisk = true;
            for (GraphEdge edge : item.risks()) {
                sb.append(riskLine(edge));
            }
        }

        sb.append("\n回答要求：\n");
        if (anyRisk) {
            sb.append("- 有风险的项：如实说明是什么风险、和什么一起会出问题，并带上出处（《文档名》）。\n")
                    .append("- 涉及处方药或已确诊疾病的，提醒用户遵医嘱，不要给出「可以吃/不能吃」的处方级结论。\n");
        }
        sb.append("- 「图谱中没有收录」的项：明确说平台没有收录这样东西，"
                + "**不要说成「没有冲突」**——这两句话在这个场景里意思相反。\n")
                .append("- 「已收录 + 未发现风险」的项：可以说未发现已知相互作用，但要与「没有收录」分开表述。\n")
                .append("- 只引用上面出现的出处，不要补充图谱之外的知识。\n");
        if (anyMissing) {
            // 让模型有下一步可走，而不是卡在「查不到」上——商品名不完整是最常见的原因
            sb.append("- 未收录的是商品时，先调 product_search 拿到准确商品名或编号，再重新检查一次。\n");
        }
        return sb.toString();
    }

    /** 「深海鱼油、EPA、DHA」——不含根节点自身，去重后按展开顺序排列 */
    private String substanceLine(InteractionReport.Item item) {
        Set<String> names = new LinkedHashSet<>();
        for (Substance s : item.substances()) {
            if (s.name().equals(s.rootName())) {
                continue;
            }
            names.add(label(s.label(), s.name()) + kindSuffix(s.kind()));
        }
        return String.join("、", names);
    }

    private String riskLine(GraphEdge edge) {
        StringBuilder sb = new StringBuilder("  ⚠ ")
                .append(relationLabel(edge.relation())).append("：与「")
                .append(counterpartLabel(edge)).append("」");

        if (notBlank(edge.effect())) {
            sb.append(" —— ").append(edge.effect().strip());
        }
        sb.append("\n");

        if (edge.chain() != null && edge.chain().size() > 1) {
            sb.append("      关联路径：").append(String.join(" → ", edge.chain())).append("\n");
        }
        sb.append("      出处：").append(notBlank(edge.docTitle())
                        ? "《" + edge.docTitle() + "》" : nullSafe(edge.docId()))
                .append("｜原文：「").append(truncate(edge.quote())).append("」\n");
        return sb.toString();
    }

    /**
     * 这条边上是「对方」的那一端。
     * <p>
     * 用 {@code counterpart} 而不是 {@code tail}：{@code head}/{@code tail} 保留图的真实方向
     * （{@code CAUTION_FOR} 的方向有语义，不能翻），所以查药物时两端可能都是它自己——
     * 直接渲染 head/tail 会输出「华法林 与 华法林 有相互作用」。
     */
    private String counterpartLabel(GraphEdge edge) {
        if (edge.counterpart() != null) {
            return label(edge.counterpart().label(), edge.counterpart().name());
        }
        // counterpart 是相互作用查询补的；万一缺失，退到「不是自己的那一端」
        return edge.tail() == null ? "" : label(edge.tail().label(), edge.tail().name());
    }

    /**
     * 关系的中文说法由 {@link GraphRelation} 提供，不在这里写 switch。
     * <p>
     * 词表是共享的（ai-service 拿它拼抽取提示词、knowledge-service 拿它校验入库），
     * 显示名再各写一份的话，加一条关系时必然漏掉某一边。
     */
    private String relationLabel(String relation) {
        GraphRelation parsed = GraphRelation.parse(relation);
        return parsed == null ? nullSafe(relation) : parsed.label();
    }

    /** kind 是枚举名。解析不出来就不显示，宁可少一个括号也不要漏出 {@code DRUG_CLASS} 这种词 */
    private String kindSuffix(String kind) {
        EntityKind parsed = EntityKind.parse(kind);
        return parsed == null ? "" : "（" + parsed.label() + "）";
    }

    private String label(String label, String fallback) {
        return notBlank(label) ? label : nullSafe(fallback);
    }

    private String truncate(String quote) {
        if (!notBlank(quote)) {
            return "（原文缺失）";
        }
        String stripped = quote.strip();
        return stripped.length() <= QUOTE_CHARS ? stripped : stripped.substring(0, QUOTE_CHARS) + "…";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.strip();
    }
}
