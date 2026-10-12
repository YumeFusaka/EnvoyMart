package yumefusaka.envoymart.agent.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRelationTest {

    @Test
    void 组合形状与抽取签名使用同一词表() {
        assertThat(GraphRelation.COMBINED_WITH.acceptsHead(EntityKind.COMBINATION)).isTrue();
        assertThat(GraphRelation.COMBINED_WITH.acceptsHead(EntityKind.NUTRIENT)).isFalse();
        assertThat(GraphRelation.COMBINED_WITH.acceptsTail(EntityKind.NUTRIENT)).isTrue();
        assertThat(GraphRelation.COMBINED_WITH.acceptsTail(EntityKind.INGREDIENT)).isTrue();
        assertThat(GraphRelation.COMBINED_WITH.acceptsTail(EntityKind.PRODUCT)).isFalse();
        assertThat(GraphRelation.COMBINED_WITH.signature()).startsWith("组合 -> ");
        assertThat(GraphRelation.COMBINED_WITH.label()).isEqualTo("组合注意事项");
    }
}
