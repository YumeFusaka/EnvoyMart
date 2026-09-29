package yumefusaka.envoymart.aiservice.knowledge;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.contract.GraphTriplePayload;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽取结果的解析边界 —— 全部围绕同一个区分：<b>「没抽到」和「没抽成」不是一回事</b>。
 * <p>
 * {@code parse} 返回 {@code null} 表示这次解析失败，调用方据此<b>不发写入请求</b>；
 * 返回空列表表示这篇文档确实没有可抽的关系，可以写（写入语义是整体替换，
 * 写空等于清掉这篇文档已有的边）。
 * <p>
 * 第一版把两者都返回空列表，于是模型输出被 {@code max_tokens} 截断一次，
 * 那篇文档图上已经建好的边就在一次重建里全部消失，而报告上失败数是 0、
 * 日志只写着「抽取 0 条」——窗口期内用户问「这两个能不能一起吃」得到「未收录」。
 * 这是安全场景里的假阴性，所以这条边界值得单独钉住。
 */
class KnowledgeGraphBuilderParseTest {

    private static final String GOOD = """
            {"triples":[{"headKind":"INGREDIENT","headName":"深海鱼油","relation":"INTERACTS_WITH",
            "tailKind":"DRUG","tailName":"华法林","effect":"可能增加出血风险","quote":"与华法林合用可能增加出血风险"}]}
            """;

    @Test
    void 正常输出解析出三元组() {
        List<GraphTriplePayload> parsed = KnowledgeGraphBuilder.parse(GOOD);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getHeadName()).isEqualTo("深海鱼油");
        assertThat(parsed.get(0).getRelation()).isEqualTo("INTERACTS_WITH");
    }

    @Test
    void 被截断的JSON必须返回null而不是空列表() {
        // 这正是 KB-0010 上发生过的事：模型抽到了关系，输出被截在第 2048 个 Token 上，
        // 字符串断在半路。返回空列表的话这一篇的旧边会被清空
        String truncated = GOOD.substring(0, GOOD.length() - 60);

        assertThat(KnowledgeGraphBuilder.parse(truncated)).isNull();
    }

    @Test
    void 回复里根本没有JSON时返回null() {
        assertThat(KnowledgeGraphBuilder.parse("我无法从这篇文档中提取任何关系。")).isNull();
    }

    @Test
    void 空回复返回null() {
        // 空回复和「真的没有关系」无法区分，按失败处理——不写请求的代价只是这一篇没更新
        assertThat(KnowledgeGraphBuilder.parse(null)).isNull();
        assertThat(KnowledgeGraphBuilder.parse("   ")).isNull();
    }

    @Test
    void 解析成功但没有triples字段时返回null() {
        // 模型换了输出格式，与「这篇确实没有可抽的关系」不是一回事。
        // 后者模型会按提示词回 {"triples":[]}
        assertThat(KnowledgeGraphBuilder.parse("{\"result\":\"none\"}")).isNull();
    }

    @Test
    void 明确回空的triples是合法的空列表() {
        // 这一条必须与上面几条分开：它意味着「这篇文档确实没有关系」，
        // 允许写入（会把这篇文档的边清空——那正是正确的结果）
        assertThat(KnowledgeGraphBuilder.parse("{\"triples\":[]}")).isNotNull().isEmpty();
    }

    @Test
    void 容忍markdown代码块与前后废话() {
        // 模型很爱回 ```json ... ```。这段围栏会让反序列化直接失败，
        // 而一次失败就丢掉整篇文档的关系，起因只是多了三个反引号
        assertThat(KnowledgeGraphBuilder.parse("好的，结果如下：\n```json\n" + GOOD + "\n```\n希望有帮助。"))
                .hasSize(1);
    }

    @Test
    void 缺relation的条目被过滤而不是让整篇失败() {
        String mixed = """
                {"triples":[{"headKind":"INGREDIENT","headName":"深海鱼油","tailKind":"DRUG","tailName":"华法林"},
                {"headKind":"INGREDIENT","headName":"深海鱼油","relation":"INTERACTS_WITH",
                 "tailKind":"DRUG","tailName":"华法林","quote":"与华法林合用可能增加出血风险"}]}
                """;

        List<GraphTriplePayload> parsed = KnowledgeGraphBuilder.parse(mixed);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getRelation()).isEqualTo("INTERACTS_WITH");
    }
}
