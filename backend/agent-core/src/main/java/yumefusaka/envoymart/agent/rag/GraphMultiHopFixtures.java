package yumefusaka.envoymart.agent.rag;

import java.util.List;

/** 真实图谱多跳能力的 live 评测问题，锚点逐字来自当前知识语料。 */
public final class GraphMultiHopFixtures {

    public record Case(String id, String question, boolean expectRefuse, List<String> mustMention) {
        public Case {
            mustMention = List.copyOf(mustMention);
        }
    }

    public static final List<Case> CASES = List.of(
            new Case("KG-01", "鱼油中的 EPA 和 DHA 与华法林合用有什么风险？", false, List.of("EPA", "DHA", "华法林", "出血风险")),
            new Case("KG-02", "碳酸钙 D3 咀嚼片里的钙与喹诺酮抗生素会相互影响吗？", false, List.of("钙", "喹诺酮类抗生素", "2 小时")),
            new Case("KG-03", "铁叶酸片中的铁会影响左旋多巴吸收吗？", false, List.of("铁剂", "左旋多巴", "2 小时")),
            new Case("KG-04", "维生素 K2 和华法林之间是什么关系？", false, List.of("维生素 K", "华法林", "拮抗")),
            new Case("KG-05", "磷虾油和阿司匹林一起用要注意什么？", false, List.of("磷虾油", "阿司匹林", "出血风险")),
            new Case("KG-06", "褪黑素会不会影响华法林或降压药？", false, List.of("褪黑素", "华法林", "代谢")),
            new Case("KG-07", "辅酶 Q10 对华法林的抗凝作用有什么影响？", false, List.of("辅酶 Q10", "华法林", "抗凝作用")),
            new Case("KG-08", "孕期 DHA 藻油和抗凝药合用有什么风险？", false, List.of("DHA", "抗凝药物", "出血风险")),
            new Case("KG-09", "儿童钙软糖和四环素类药物为什么要错开？", false, List.of("钙", "四环素类", "2 小时")),
            new Case("KG-10", "益生菌咀嚼片能和抗生素同一时间吃吗？", false, List.of("益生菌", "抗生素", "2 小时")),
            new Case("KG-11", "乳清蛋白粉对乳糖不耐受者有什么选择建议，和左旋多巴呢？", false, List.of("乳糖", "左旋多巴")),
            new Case("KG-12", "高纯度鱼油的 EPA、DHA 与氯吡格雷合用有什么注意事项？", false, List.of("EPA", "DHA", "氯吡格雷", "出血风险"))
    );

    private GraphMultiHopFixtures() {
    }
}
