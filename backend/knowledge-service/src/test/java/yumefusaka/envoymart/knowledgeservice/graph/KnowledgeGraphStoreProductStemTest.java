package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品展示名的口语词干抽取。
 * <p>
 * 回归背景：商品节点的展示名带剂型后缀（「磷虾油软胶囊」），而用户嘴里的写法是它的词干
 * （「磷虾油」）。实体链接原先只拿完整展示名做子串判断，于是「磷虾油和抗凝药同时吃有什么风险」
 * 一个实体都链接不上——这个商品的说明书边（KB-0049）永远进不了召回；写成
 * 「磷虾油软胶囊 抗凝药」却能召回 KB-0049_2。判据差异只在这一层。
 */
class KnowledgeGraphStoreProductStemTest {

    @Test
    void 剥掉尾部剂型后缀得到口语词干() {
        assertThat(KnowledgeGraphStore.productNameStems("磷虾油软胶囊"))
                .as("用户说的「磷虾油」必须能对上商品节点「磷虾油软胶囊」")
                .contains("磷虾油")
                .doesNotContain("磷虾油软胶囊");
    }

    @Test
    void 先剥尾部括号注释再剥剂型后缀() {
        assertThat(KnowledgeGraphStore.productNameStems("深海鱼油胶丸（高纯度）"))
                .containsExactly("深海鱼油胶丸", "深海鱼油");
    }

    @Test
    void 每个剂型后缀各生成一个词干而不是只剥最长的那个() {
        assertThat(KnowledgeGraphStore.productNameStems("全营养配方粉"))
                .as("「配方粉」与「粉」都要试：「全营养」与「全营养配方」都可能是用户的说法")
                .containsExactly("全营养", "全营养配方");
    }

    @Test
    void 剥括号后的原文形式词干也保留() {
        assertThat(KnowledgeGraphStore.productNameStems("维生素 C 咀嚼片（免疫配方）"))
                .contains("维生素 C 咀嚼片", "维生素 C");
    }

    @Test
    void 没有剂型后缀时不给词干() {
        assertThat(KnowledgeGraphStore.productNameStems("褪黑素")).isEmpty();
    }

    @Test
    void 名称为空或纯空白时不给词干() {
        assertThat(KnowledgeGraphStore.productNameStems(null)).isEmpty();
        assertThat(KnowledgeGraphStore.productNameStems("")).isEmpty();
        assertThat(KnowledgeGraphStore.productNameStems("  ")).isEmpty();
    }

    @Test
    void 词干不返回原名且长度都过下限() {
        assertThat(KnowledgeGraphStore.productNameStems("复合维生素矿物质片"))
                .containsExactly("复合维生素矿物质");
        for (String label : new String[]{"磷虾油软胶囊", "深海鱼油胶丸（高纯度）", "全营养配方粉",
                "维生素 C 咀嚼片（免疫配方）", "复合维生素矿物质片", "褪黑素"}) {
            assertThat(KnowledgeGraphStore.productNameStems(label))
                    .allSatisfy(stem -> assertThat(stem.length()).isGreaterThanOrEqualTo(2));
        }
    }
}
