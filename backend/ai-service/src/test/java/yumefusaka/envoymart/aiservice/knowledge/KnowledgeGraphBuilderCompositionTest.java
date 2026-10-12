package yumefusaka.envoymart.aiservice.knowledge;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.contract.GraphTriplePayload;
import yumefusaka.envoymart.contract.ProductSummary;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphBuilderCompositionTest {

    private static GraphTriplePayload relation(String head, String relation, String tail, String quote) {
        return GraphTriplePayload.builder().headKind("NUTRIENT").headName(head)
                .relation(relation).tailKind("NUTRIENT").tailName(tail).quote(quote).build();
    }

    @SuppressWarnings("unchecked")
    private static List<GraphTriplePayload> augment(Document document, List<GraphTriplePayload> triples)
            throws Exception {
        var builder = new KnowledgeGraphBuilder(null, null, null, null);
        Method method = KnowledgeGraphBuilder.class.getDeclaredMethod("augmentSubjectEdges",
                Document.class, List.class, List.class);
        method.setAccessible(true);
        List<ProductSummary> catalog = document.getSubjectSpuIds().stream()
                .map(id -> ProductSummary.builder().id(id).name("测试商品" + id).build()).toList();
        return (List<GraphTriplePayload>) method.invoke(builder, document, triples, catalog);
    }

    @Test
    void 钙片的相互作用对象铁锌不能被补成商品成分() throws Exception {
        String composition = "本品为膳食营养补充剂，含氨基葡萄糖、钙与维生素 D3。";
        String interaction = "钙与铁、锌同服存在吸收竞争，需要同时补充时建议错开服用。";
        Document document = Document.builder().id("KB-0047").subjectSpuIds(List.of(39L))
                .content(composition + "\n" + interaction).build();
        List<GraphTriplePayload> triples = List.of(
                relation("氨基葡萄糖", "CAUTION_FOR", "过敏者", composition),
                relation("钙", "INTERACTS_WITH", "铁", interaction),
                relation("钙", "INTERACTS_WITH", "锌", interaction),
                relation("维生素D3", "INTERACTS_WITH", "抗凝药物", composition));

        assertThat(augment(document, triples).stream().filter(t -> "CONTAINS".equals(t.getRelation()))
                .map(GraphTriplePayload::getTailName)).containsExactly("氨基葡萄糖", "钙", "维生素D3");
    }

    @Test
    void 只有共服提示和主体绑定时不能推出商品含有任何成员() throws Exception {
        String body = "本品为SPU26儿童钙软糖。钙剂、铁剂、锌三者同时补充时，在肠道吸收阶段会发生竞争并彼此抑制。";
        Document document = Document.builder().id("KB-0053").subjectSpuIds(List.of(26L)).content(body).build();
        GraphTriplePayload combination = relation("钙剂+铁剂+锌", "COMBINED_WITH", "锌", body);
        combination.setHeadKind("COMBINATION");

        assertThat(augment(document, List.of(combination)).stream()
                .filter(t -> "CONTAINS".equals(t.getRelation()))).isEmpty();
    }

    @Test
    void 否定组成及同服对象不能支撑补边() throws Exception {
        String body = "本品不含乳糖。本品与其他含铁制剂同服需注意。";
        Document document = Document.builder().id("KB-NEGATIVE").subjectSpuIds(List.of(39L)).content(body).build();
        assertThat(augment(document, List.of(relation("乳糖", "INTERACTS_WITH", "铁", body))).stream()
                .filter(t -> "CONTAINS".equals(t.getRelation()))).isEmpty();
    }

    @Test
    void 本品所含之后的共服对象不能混进组成声明() throws Exception {
        String body = "本品所含钙与铁剂合用时会相互影响吸收，补钙与补铁建议间隔至少两小时。";
        Document document = Document.builder().id("KB-0034").subjectSpuIds(List.of(26L)).content(body).build();
        List<GraphTriplePayload> added = augment(document,
                List.of(relation("钙", "INTERACTS_WITH", "铁剂", body))).stream()
                .filter(t -> "CONTAINS".equals(t.getRelation())).toList();
        assertThat(added).extracting(GraphTriplePayload::getTailName).containsExactly("钙");
        assertThat(added.getFirst().getQuote()).isEqualTo(body.substring(0, body.length() - 1));
    }

    @Test
    void 成分提供营养素保留两跳关系不直接补成商品含营养素() throws Exception {
        String body = "本品为膳食营养补充剂，主要成分为精制深海鱼油，提供二十碳五烯酸（EPA）。";
        Document document = Document.builder().id("KB-0006").subjectSpuIds(List.of(5L)).content(body).build();
        GraphTriplePayload provides = relation("深海鱼油", "PROVIDES", "EPA", body);
        provides.setHeadKind("INGREDIENT");
        List<GraphTriplePayload> output = augment(document, List.of(provides));
        assertThat(output).contains(provides);
        assertThat(output.stream().filter(t -> "CONTAINS".equals(t.getRelation()))
                .map(GraphTriplePayload::getTailName)).containsExactly("深海鱼油");
    }

    @Test
    void 多主体说明书不把两个商品各自成分补成相同配方() throws Exception {
        String body = "本品涵盖两个商品：褪黑素片含褪黑素，GABA软糖含γ-氨基丁酸。";
        Document document = Document.builder().id("KB-0017").subjectSpuIds(List.of(1L, 2L)).content(body).build();
        GraphTriplePayload first = relation("SPU1", "CONTAINS", "褪黑素", body);
        first.setHeadKind("PRODUCT");
        GraphTriplePayload second = relation("SPU2", "CONTAINS", "γ-氨基丁酸", body);
        second.setHeadKind("PRODUCT");
        assertThat(augment(document, List.of(first, second))).containsExactly(first, second);
    }

    @Test
    void 逗号后的其他制剂不得继承本品组成主语() throws Exception {
        for (String body : List.of(
                "本品含钙，含铁制剂应与本品间隔服用。",
                "本品含钙，含铁制剂与本品同服会影响吸收。",
                "本品含钙，含铁制剂与本品会影响吸收。",
                "本品含钙，含铁制剂不宜与本品一起使用。",
                "本品含钙，其他制剂每粒含铁10毫克，与本品应间隔服用。",
                "本品含钙，正在服药者应咨询医师，并添加铁剂不是本品配方。")) {
            Document document = Document.builder().id("KB-SUBJECT").subjectSpuIds(List.of(26L))
                    .content(body).build();
            assertThat(augment(document, List.of(relation("钙", "INTERACTS_WITH", "铁", body))).stream()
                    .filter(t -> "CONTAINS".equals(t.getRelation()))
                    .map(GraphTriplePayload::getTailName)).as(body).containsExactly("钙");
        }
    }

    @Test
    void 共服句不能把隔句本品当成组成主语() throws Exception {
        String body = "含铁制剂应与本品间隔服用。本品为钙片。";
        Document document = Document.builder().id("KB-OTHER-SUBJECT").subjectSpuIds(List.of(26L))
                .content(body).build();
        assertThat(augment(document, List.of(relation("钙", "INTERACTS_WITH", "铁", body))).stream()
                .filter(t -> "CONTAINS".equals(t.getRelation()))).isEmpty();
    }

    @Test
    void 明确规格字段每粒含量及同主语添加保留() throws Exception {
        for (String body : List.of(
                "规格为每粒含钙100毫克、维生素D100IU。",
                "每粒含钙100毫克、维生素D100IU。",
                "主要成分为钙与维生素D。",
                "本品为膳食营养补充剂，主要成分为钙，并添加维生素D以促进钙吸收。",
                "本品含钙，含有维生素D。")) {
            Document document = Document.builder().id("KB-COMPOSITION").subjectSpuIds(List.of(26L))
                    .content(body).build();
            assertThat(augment(document, List.of(relation("钙", "INTERACTS_WITH", "维生素D", body))).stream()
                    .filter(t -> "CONTAINS".equals(t.getRelation()))
                    .map(GraphTriplePayload::getTailName)).as(body).containsExactly("钙", "维生素D");
        }
    }
}
