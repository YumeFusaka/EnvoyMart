package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「知识依据」段的渲染契约。
 * <p>
 * 锁的不是措辞好不好看，而是<b>模型能不能据此给出可核对的引用</b>：
 * 位置与版本有没有进去、编号规则有没有写死、拒答态有没有把条目一起塞回去。
 * 这些一旦退化，表现是回答照样流畅、只是引用变成了摆设。
 */
class KnowledgePromptTest {

    private static final EvidenceGate.Thresholds T = EvidenceGate.Thresholds.defaults();

    /**
     * 判定与渲染是两件事，但每条用例都只关心渲染结果。
     * <p>
     * 这里替用例把判定算出来——真实调用方（{@code Agent}）也是这么用的：
     * 算一次，prompt 与响应体共用。签名的门道由 {@link EvidenceGateTest} 覆盖。
     */
    private static KnowledgePrompt.Section render(List<DocumentChunk> chunks) {
        return KnowledgePrompt.render(chunks, EvidenceGate.evaluate(chunks, T));
    }

    private DocumentChunk chunk(String id, String position, Double score) {
        return DocumentChunk.builder()
                .chunkId(id + "_0").docId(id)
                .title("维生素 D3 软胶囊说明书")
                .position(position)
                .source("manual").version("v2026.03")
                .scope("nutrition")
                .content("每日推荐摄入量为 400IU，上限 2000IU。")
                .score(score).reranked(Boolean.FALSE)
                .build();
    }

    @Test
    void 证据充分时带编号位置来源版本并强制标注引用() {
        var section = render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章 > 3.2", 0.78)));

        assertThat(section.decision().isSufficient()).isTrue();
        assertThat(section.text())
                .contains("[1]")
                .contains("第二章 > 3.2")
                .contains("来源：manual")
                .contains("版本：v2026.03")
                .contains("每日推荐摄入量为 400IU")
                .contains("标注来源编号")
                .contains("不得编造编号");
    }

    /**
     * 冲突处理指令必须与抽取端用的是<b>同一个标记</b>。
     * <p>
     * 模型按别的写法回（「[冲突]」「（冲突）」），{@link ConflictReporter} 就抽不到——
     * 表现不是报错，而是冲突悄悄退化成回答末尾一段没人看得懂的纯文本。
     * <p>
     * 另外两条锁的是<b>误报的边界</b>：判定标准写宽一格（比如「写法不一致也算」），
     * 模型就会把「10 微克（折合 400 国际单位）」与「400IU」这种同一数值的不同写法
     * 当成冲突报上来，用户收到一张并不存在的冲突卡片。抽取端有兜底
     * （{@link ConflictReporter} 会丢弃自陈一致的段落），但那是最后一道，
     * 不能拿它当借口把 prompt 写松。
     */
    @Test
    void 证据充分时要求模型按约定标记列出冲突() {
        var section = render(List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.80)));

        assertThat(section.text())
                .contains(ConflictReporter.MARKER)
                .as("判定标准必须锚在「数字、期限或结论不一样」上")
                .contains("数字、期限或结论不一样")
                .as("核对下来一致的段落不许写，抽取端会把它丢掉")
                .contains("确认过一致的事不要写进这个段落");
    }

    @Test
    void 多条证据各自编号且顺序与输入一致() {
        var section = render(List.of(
                chunk("d1", "《甲》 > 第一章", 0.80),
                chunk("d2", "《乙》 > 第二章", 0.70)));

        assertThat(section.text())
                .contains("[1] 《甲》 > 第一章")
                .contains("[2] 《乙》 > 第二章");
    }

    /**
     * 拒答态的措辞必须显式在场。
     * <p>
     * 没有这段指令时，模型面对空证据的默认行为是「用自己的知识答」——
     * 而这恰恰是编造条款与数字的来源。
     */
    @Test
    void 没有召回时给出拒答指令且不混入任何条目() {
        var section = render(List.of());

        assertThat(section.decision().level()).isEqualTo(EvidenceGate.Level.NONE);
        assertThat(section.text())
                .contains("没有找到相关依据")
                .contains("不得依据常识、经验或训练数据编造")
                .doesNotContain("[1]");
    }

    @Test
    void 证据不足时明确禁止作为结论依据但仍保留线索() {
        var section = render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)));

        assertThat(section.decision().level()).isEqualTo(EvidenceGate.Level.WEAK);
        assertThat(section.text())
                .contains("不得作为结论依据")
                .contains("依据不足")
                .as("低相关度条目仍要展示，让用户知道系统找到了什么")
                .contains("第二章");
    }

    /**
     * 证据不足的两个分支要给「先换词再查一次」这条出路 —— <b>但只在工具真的在册时</b>。
     * <p>
     * 不给这条出路时，WEAK / NONE 对模型是一份自洽的行动方案（不下结论、如实说没查到），
     * 它照着办就很合理：实测工具在册却一轮都没被调用过。给了不存在的能力则更糟——
     * 模型会去调一个没有的工具，日志上只表现为「没有调用任何工具」。
     */
    @Test
    void 再检索工具在册时给出换词再查的出路() {
        var weak = KnowledgePrompt.render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)),
                EvidenceGate.evaluate(List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)), T),
                true);
        var none = KnowledgePrompt.render(List.of(), EvidenceGate.evaluate(List.of(), T), true);

        assertThat(weak.text())
                .contains(KnowledgePrompt.SEARCH_TOOL_NAME)
                .as("要说清换成什么词，否则模型会把同一句问话原样再查一遍")
                .contains("不要原样重复问句")
                .as("给了出路也不能松掉拒答底线")
                .contains("不得作为结论依据");
        assertThat(none.text()).contains(KnowledgePrompt.SEARCH_TOOL_NAME).contains("没有找到相关依据");
    }

    @Test
    void 工具不在册时两个分支都不提再检索() {
        var weak = render(List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)));
        var none = render(List.of());

        assertThat(weak.text()).doesNotContain(KnowledgePrompt.SEARCH_TOOL_NAME);
        assertThat(none.text()).doesNotContain(KnowledgePrompt.SEARCH_TOOL_NAME);
        assertThat(render(List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.78))).text())
                .as("证据充分时没有理由再查一次，这条出路不该出现")
                .doesNotContain(KnowledgePrompt.SEARCH_TOOL_NAME);
    }

    /**
     * 证据不足时<b>不下发「低于可信阈值」这句内部判断</b>。
     * <p>
     * 曾经这里写着「相关度 0.12 低于可信阈值」，模型就照着念给用户听
     * （实测原话：「该条目相关度低于可信阈值，不能作为权威依据」）——
     * 系统在替自己免责，用户读到的却是「你可能查到了什么，但我不告诉你」。
     * <p>
     * 保留的 {@code 相关度 0.12} 是切片的客观属性（和版本号同一类），
     * 模型要靠它横向比较几条依据；被拿掉的是那句阈值判决。
     * 判决本身仍进日志：{@link EvidenceGate.Decision#topScore()}。
     */
    @Test
    void 证据不足时不下发阈值判决只下发条目事实() {
        var section = render(
                List.of(chunk("d1", "《维生素 D3 软胶囊说明书》 > 第二章", 0.12)));

        assertThat(section.text())
                .doesNotContain("低于可信阈值")
                .contains("不要向用户提及相关度")
                .contains("相关度 0.12");
        assertThat(section.decision().topScore()).isEqualTo(0.12);
    }

    /**
     * 位置缺失时退到标题，<b>不能退到 docId</b>。
     * <p>
     * 曾经 {@code KnowledgeSnippet.title} 填的就是 docId，前端渲染成「[1] doc_12」——
     * 一串内部 ID 摆在用户面前，既不能核对也不能理解，却看起来像一条引用。
     */
    @Test
    void 位置缺失时退到文档标题而不是内部标识() {
        DocumentChunk noPosition = chunk("d1", null, 0.80);
        var section = render(List.of(noPosition));

        assertThat(section.text())
                .contains("《维生素 D3 软胶囊说明书》")
                .doesNotContain("d1");
    }
}
