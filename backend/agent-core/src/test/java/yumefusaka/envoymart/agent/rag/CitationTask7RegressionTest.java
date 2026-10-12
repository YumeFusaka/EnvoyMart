package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CitationTask7RegressionTest {
    @Test
    void 同名标题按照实际条件定位而不是第一片() {
        var policy = doc("退货运费补贴政策", "退货运费由买家承担。", "KB0002_0");
        var benefit = doc("退货运费补贴政策", "验收后3个工作日发放运费补贴，需上传运费凭证，同一订单仅补贴一次。", "KB0002_3");
        String reply = "验收后3个工作日发放运费补贴，需上传运费凭证，同一订单仅补贴一次（《退货运费补贴政策》）。";
        assertThat(CitationVerifier.numberTitles(reply, List.of(policy, benefit))).contains("[2]");
        assertThat(CitationVerifier.numberTitles(reply, List.of(benefit, policy))).contains("[1]");
    }

    @Test
    void 会员权益不能引用仅描述等级体系的切片() {
        String reply = "普通会员无折扣，银卡会员95折，金卡会员9折（《会员等级与权益》）。";
        assertThat(CitationVerifier.numberTitles(reply, Arrays.asList(
                doc("会员等级与权益", "会员分为普通、银卡、金卡三个等级。", "KB0015_0"), null,
                doc("会员等级与权益", "普通会员无折扣，银卡会员95折，金卡会员9折。", "KB0015_1"))))
                .contains("[3]");
    }

    @Test
    void 无法确定同名切片时保留歧义不能猜第一片() {
        String reply = "平台保障有效（《服务政策》）。";
        assertThat(CitationVerifier.numberTitles(reply, List.of(
                doc("服务政策", "买家支付运费。", "A"), doc("服务政策", "卖家办理退款。", "B"))))
                .isEqualTo(reply);
        assertThat(CitationVerifier.numberTitles("会员分三个等级 [2]。", List.of(
                doc("会员", "等级", "A"), doc("会员", "三个等级", "B"))))
                .isEqualTo("会员分三个等级 [2]。");
    }

    @Test
    void 标题定位诊断与引用改写后的稳定句标识均可追踪() {
        String reply = "每片含铁10毫克（《铁叶酸片说明书》）。";
        var numbered = CitationVerifier.numberTitlesWithDiagnostics(reply, List.of(
                doc("铁叶酸片说明书", "每片含铁15毫克。", "A"),
                doc("铁叶酸片说明书", "每片含铁10毫克。", "B")));
        assertThat(numbered.resolutions().getFirst().reason()).isEqualTo("UNIQUE_BODY");
        assertThat(CitationVerifier.diagnosticSentenceBody(reply))
                .isEqualTo(CitationVerifier.diagnosticSentenceBody(numbered.reply()));
        var ambiguous = CitationVerifier.numberTitlesWithDiagnostics("未知条件（《铁叶酸片说明书》）。", List.of(
                doc("铁叶酸片说明书", "每片含铁15毫克。", "A"),
                doc("铁叶酸片说明书", "每片含铁10毫克。", "B")));
        assertThat(ambiguous.resolutions().getFirst().reason()).isEqualTo("BODY_UNRESOLVED");
    }

    private static DocumentChunk doc(String title, String text, String id) {
        return DocumentChunk.builder().title(title).content(text).chunkId(id).score(0.9).build();
    }
}
