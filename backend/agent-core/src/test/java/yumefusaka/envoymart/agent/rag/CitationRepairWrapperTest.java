package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationRepairWrapperTest {
    @Test
    void 引用编号回写后清除已无内容的出处包装而保留事实与角标() {
        for (String wrapper : List.of("（出处：[1]）", "(来源：[1])")) {
            String sentence = "铁剂会降低左旋多巴吸收" + wrapper + "。";
            assertThat(CitationVerifier.repairCitations(sentence, Map.of(sentence, List.of(1)), List.of()))
                    .isEqualTo("铁剂会降低左旋多巴吸收 [1]。");
        }
    }

    @Test
    void 引用回写不删除仍含真实文字的出处括号() {
        String sentence = "铁剂会降低左旋多巴吸收（出处：说明书正文[1]）。";
        assertThat(CitationVerifier.repairCitations(sentence, Map.of(sentence, List.of(1)), List.of()))
                .isEqualTo("铁剂会降低左旋多巴吸收（出处：说明书正文） [1]。");
    }

    @Test
    void 空证据占位不崩溃也不能改变后续标题的原始编号() {
        var source = DocumentChunk.builder().docId("iron").title("铁剂说明书").build();
        assertThat(CitationVerifier.numberTitles("请核对《铁剂说明书》。",
                java.util.Arrays.asList(null, source))).isEqualTo("请核对[2]。");
    }
}
