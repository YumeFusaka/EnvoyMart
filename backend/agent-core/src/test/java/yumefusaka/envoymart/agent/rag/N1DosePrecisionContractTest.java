package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-14 现场固化：模型把数值写得不精确时，医学闸门**拦得对**。
 * <p>
 * 这一组用例的价值在于把「拦」的原因逐条钉住——它们看起来都像门禁过严，
 * 逐条核对下来全是句子自身的问题。没有这组断言，下一个人会以为该放宽判据。
 * <p>
 * 现场（{@code c091b3b9} 的 G-14）：问「维生素 D 每日可耐受最高摄入量是多少？」，
 * 模型答了 4 条列表项，全部被拒，最终交付兜底句。逐句核对如下。
 */
class N1DosePrecisionContractTest {

    private static final Set<String> VITAMIN_D = Set.of("维生素d");

    private static List<String> terms() throws Exception {
        var field = MedicalEvidenceVerifier.class.getDeclaredField("MEDICAL_TERMS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> value = (List<String>) field.get(null);
        return value;
    }

    @Test
    void 数值不带角色时无法核对所以拒得对() throws Exception {
        // 切片里同时有「推荐摄入量」与「可耐受最高摄入量」两条不同数值的断言，
        // 句子只写「成人：2000IU」——判据无法确认它对应哪一条
        String claim = "- 成人：2000IU，长期超过该剂量应在医生监测下进行。";
        String source = "1.1 维生素 D 的成人每日可耐受最高摄入量为 2000IU，长期超过该剂量应在医生监测下进行。";

        assertThat(MedicalDoseAssertions.rejection(claim, source, terms(), VITAMIN_D, Set.of()))
                .as("角色缺失时必须拒——否则「推荐量」与「上限」两个数值可以互相冒充")
                .isEqualTo("剂量角色不明确");
        assertThat(MedicalDoseAssertions.rejection(
                "- 成人：每日可耐受最高摄入量为 2000IU，长期超过该剂量应在医生监测下进行。",
                source, terms(), VITAMIN_D, Set.of()))
                .as("补上角色后应当放行，证明拒的是缺角色、不是数值本身")
                .isNull();
    }

    @Test
    void 一句话塞两个人群的数值时拒得对() throws Exception {
        String source = "维生素 D 推荐摄入量成人 10 微克每日（折合 400 国际单位），65 岁以上 15 微克每日，"
                + "可耐受最高摄入量 50 微克每日（折合 2000 国际单位）。";
        String merged = "- 成人维生素 D 推荐摄入量为 10 微克每日（折合 400 国际单位），65 岁以上为 15 微克每日。";

        assertThat(MedicalDoseAssertions.rejection(merged, source, terms(), VITAMIN_D, Set.of()))
                .as("两个人群的数值压进一句，后半句的主语丢失，无法逐条核对")
                .isEqualTo("数量主体与引用切片不一致");
        assertThat(MedicalDoseAssertions.rejection(
                "- 成人维生素 D 推荐摄入量为 10 微克每日。", source, terms(), VITAMIN_D, Set.of()))
                .as("拆成单条后应当放行，证明拒的是合句、不是数值本身")
                .isNull();
    }

    @Test
    void 主体张冠李戴时拒得对() throws Exception {
        // 切片原文：前句主体是「儿童维生素 D」，后句（可耐受最高摄入量 800IU）主体回到「维生素 D」
        String source = "3.3 儿童维生素 D 每日推荐摄入量为 400IU，可耐受最高摄入量为 800IU。";
        String misattributed = "- 儿童：可耐受最高摄入量为 800IU。";

        assertThat(MedicalDoseAssertions.rejection(misattributed, source, terms(), VITAMIN_D, Set.of()))
                .as("把后句的 800IU 说成「儿童的」而非「维生素 D 的」，是把相邻数值的主体混在一起")
                .isEqualTo("人口范围与引用切片不一致");
    }

    @Test
    void 主体可继承时不该拒() throws Exception {
        // 引导句「维生素 D 的每日可耐受最高摄入量如下：」声明了章节主体，
        // 列表项省略主体时应当由它接住——这一条守的是「继承链本身有效」，
        // 与上面三条的区别是：这里句子与切片逐字段对得上
        String source = "维生素 D 推荐摄入量成人 10 微克每日（折合 400 国际单位），65 岁以上 15 微克每日，"
                + "可耐受最高摄入量 50 微克每日（折合 2000 国际单位）。";
        assertThat(MedicalDoseAssertions.rejection(
                "- 换算成微克：成人可耐受最高摄入量为 50 微克每日，折合 2000 国际单位。",
                source, terms(), VITAMIN_D, Set.of()))
                .as("数值、角色、主体都对得上，继承来的主体足以完成核对")
                .isNull();
    }
}