package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突抽取的契约。
 * <p>
 * 检测本身是模型做的（字面比对分不清「2000IU」与「50 微克」是同一个数），
 * 这里锁的是<b>它把结论写下来之后，我们能不能可靠地接住</b>：
 * 接不住的表现不是报错，而是冲突悄悄退化成回答里一段没人看得懂的纯文本——
 * 用户既不知道「条目 1」是哪一篇，也没法自己点回去核对。
 */
class ConflictReporterTest {

    @Test
    void 冲突段被抽出正文并结构化() {
        var report = ConflictReporter.extract(
                "关于每日上限，两份资料的写法不一致。\n\n"
                        + "【冲突】条目 1 与条目 3：条目 1 为 2000IU，条目 3 为 4000IU，需人工确认。",
                3);

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().getFirst().refs()).containsExactly(1, 3);
        assertThat(report.conflicts().getFirst().detail())
                .contains("条目 1 为 2000IU")
                .as("标记本身不该出现在给用户看的文字里")
                .doesNotContain("【冲突】");
        assertThat(report.reply())
                .isEqualTo("关于每日上限，两份资料的写法不一致。")
                .doesNotContain("【冲突】");
    }

    /**
     * 模型几乎不会把一条冲突写在一行里——更像一个带列表的段落。
     * <p>
     * 只吃第一行的话，下面那两行会被 {@link CitationVerifier} 当成两句没出处的断言剔掉，
     * 冲突的细节反而在答案里消失，只剩一个光秃秃的标题。
     */
    /**
     * 模型自己核对完说「一致」的那一段，不是冲突。
     * <p>
     * 两条 detail 抄的都是<b>线上实测的原话</b>，不是编的：同一个问题问四次，
     * 三次不报、一次报了。prompt 改了两轮也压不住——采样有随机性，兜在抽取层才是确定的。
     * <p>
     * 两条要留在一起看：它们是<b>同一个语义的两种措辞</b>。第一版判据按措辞
     * 枚举（「不构成矛盾」「数值一致」……），第一条能拦住，第二条
     * （「在换算关系上一致，无实质冲突」）一个词都没命中。判据因此改成认结论词，
     * 这两条就是那次改动的回归证据。
     * <p>
     * 覆盖面就到实测为止：模型还有别的说法（「同一个上限的不同写法」——「不同」
     * 会被 {@link ConflictReporter#DISAGREES} 判成真冲突）拦不住，那会多给一张卡片。
     * 这是<b>刻意选的失败方向</b>：宁可多报让人自己核对，也不能放宽判据去吞真冲突。
     * <p>
     * 正文里那段也要删掉：用户要的是结论，不是一次并无分歧的核对过程。
     */
    @Test
    void 模型自陈一致的冲突段被丢弃() {
        String[] 自陈一致的原话 = {
            "【冲突】条目 1 与条目 2 对推荐摄入量的表述方式不同：条目 1 写「10 微克（折合 400 国际单位）」，"
                    + "条目 2 写「健康成年人每日维生素 D 推荐摄入量为 400IU」，数值一致，仅为单位与表述差异，不构成实质冲突。",
            "【冲突】条目 1 与条目 2、3 对成人推荐摄入量的表述略有差异：条目 1 写“10 微克（折合 400IU）”，"
                    + "条目 2 写“400IU”，条目 3 未提推荐量只提上限。三者在 400IU/10 微克这一换算关系上一致，无实质冲突。",
        };

        for (String 段落 : 自陈一致的原话) {
            var report = ConflictReporter.extract("成人每日推荐摄入量为 10 微克（即 400 国际单位）[1]。\n\n" + 段落, 3);

            assertThat(report.conflicts())
                    .as("一张「并不存在的冲突」卡片会同时误导用户与面试官，比不显示更糟：%s", 段落)
                    .isEmpty();
            assertThat(report.reply())
                    .as("段落照删：它不该变成卡片，也不该留在正文里")
                    .isEqualTo("成人每日推荐摄入量为 10 微克（即 400 国际单位）[1]。");
        }
    }

    /**
     * 反向护栏之二：模型<b>忘了</b>写「需人工确认」时，还有「不一致」这类比对否定词兜着。
     * <p>
     * 这一段里同时有「一致」（藏在「不一致」里）和「不一致」，是判据最容易翻车的形状。
     */
    @Test
    void 自陈一致但同时说了不一致的段落仍然作为冲突保留() {
        var report = ConflictReporter.extract(
                "正文 [1]。\n\n"
                        + "【冲突】条目 1 与条目 2 的适用范围相同，但对每日上限的表述不一致："
                        + "条目 1 为 2000IU，条目 2 为 4000IU。",
                2);

        assertThat(report.conflicts()).hasSize(1);
    }

    /**
     * 反向护栏：模型在同一段里既说「一致」又请人来定夺，是它自己没写清楚。
     * <p>
     * 这种情况宁可多给一张卡片，也不能替用户把一处真实分歧吞掉——误报看得见，
     * 漏报看不见，两者代价不对等。
     */
    @Test
    void 自陈一致但同时请人定夺的段落仍然作为冲突保留() {
        var report = ConflictReporter.extract(
                "正文 [1]。\n\n"
                        + "【冲突】条目 1 与条目 2 的数值一致与否需人工确认：条目 1 为 20 毫克，条目 2 为 18 毫克。",
                2);

        assertThat(report.conflicts())
                .as("「需人工确认」在场就不做丢弃")
                .hasSize(1);
        assertThat(report.conflicts().getFirst().refs()).containsExactly(1, 2);
    }

    /** 真冲突的 detail 里写着「数值不一致」——它含「一致」二字，但绝不能因此被当成自陈一致 */
    @Test
    void 数值不一致的真冲突不被自陈一致的判据误伤() {
        var report = ConflictReporter.extract(
                "成年女性每日铁推荐摄入量为 18 毫克 [2]。\n\n"
                        + "【冲突】条目 1 与条目 2 对成年女性铁推荐摄入量的数值不一致：条目 1 为 20 毫克，"
                        + "条目 2 为 18 毫克，需人工确认以哪一份为准。",
                2);

        assertThat(report.conflicts()).hasSize(1);
    }

    @Test
    void 多行冲突段落整体抽出并折成一条() {
        var report = ConflictReporter.extract(
                "其余内容。\n\n"
                        + "【冲突】关于每日上限：\n"
                        + "- 条目 1 称 2000IU\n"
                        + "- 条目 3 称 4000IU\n"
                        + "\n"
                        + "以上需人工确认。",
                3);

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().getFirst().refs()).containsExactly(1, 3);
        assertThat(report.conflicts().getFirst().detail()).contains("条目 1 称 2000IU").contains("条目 3 称 4000IU");
        assertThat(report.reply())
                .as("块外的内容原样留下，块连同它后面那个空行一起消失")
                .isEqualTo("其余内容。\n\n以上需人工确认。");
    }

    @Test
    void 三种编号写法都认得() {
        var report = ConflictReporter.extract(
                "【冲突】[2] 与第 1 条对时效的说法不同，条目 4 则介于两者之间。", 5);

        assertThat(report.conflicts().getFirst().refs()).containsExactly(1, 2, 4);
    }

    /**
     * 指向不存在条目的编号直接丢弃，不猜。
     * <p>
     * 猜错的跳转比没有跳转更坏——它会把用户带到一条与冲突无关的原文面前，
     * 而用户会以为自己核对过了。
     */
    @Test
    void 越界编号被丢弃而不是当成证据() {
        var report = ConflictReporter.extract("【冲突】条目 1 与条目 9 说法不同。", 3);

        assertThat(report.conflicts().getFirst().refs()).containsExactly(1);
        assertThat(report.conflicts().getFirst().detail()).contains("条目 9");
    }

    @Test
    void 认不出编号时不编造引用只保留文字() {
        var report = ConflictReporter.extract("【冲突】两份资料对上限的写法不一致，需人工确认。", 3);

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().getFirst().refs()).isEmpty();
        assertThat(report.conflicts().getFirst().detail()).contains("需人工确认");
    }

    @Test
    void 连排的多个标记只抽一次不重复消费() {
        var report = ConflictReporter.extract(
                "【冲突】条目 1 称 2000IU\n【冲突】条目 3 称 4000IU", 3);

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().getFirst().detail()).contains("条目 1 称 2000IU").contains("条目 3 称 4000IU");
        assertThat(report.reply()).isEmpty();
    }

    @Test
    void 没有冲突时正文逐字不动() {
        String reply = "每日上限为 2000IU [1]。";

        var report = ConflictReporter.extract(reply, 1);

        assertThat(report.conflicts()).isEmpty();
        assertThat(report.reply()).isEqualTo(reply);
    }

    @Test
    void 空回答不报错() {
        assertThat(ConflictReporter.extract(null, 3).reply()).isNull();
        assertThat(ConflictReporter.extract("", 3).conflicts()).isEmpty();
        assertThat(ConflictReporter.extract("  ", 3).conflicts()).isEmpty();
    }
}
