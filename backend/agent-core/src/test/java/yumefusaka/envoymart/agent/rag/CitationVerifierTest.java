package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用校验的判据契约。
 * <p>
 * 这个类的难点不在「怎么找出没引用的句子」，而在<b>什么算需要引用的句子</b>——
 * 判太松会把订单号、物流时效一起误伤（它们来自工具返回，本来就没有引用可标），
 * 判太严等于没做。下面每个用例锁的都是这条边界上的一处取舍。
 */
class CitationVerifierTest {

    @Test
    void 讲事实却没有出处的句子被剔除() {
        var verdict = CitationVerifier.verify(
                "每日推荐摄入量为 400IU [1]。可耐受最高摄入量为 2000IU [2]。孕妇每日摄入不得超过 4000IU。",
                2, false);

        assertThat(verdict.stripped()).isTrue();
        assertThat(verdict.unsupported()).containsExactly("孕妇每日摄入不得超过 4000IU。");
        assertThat(verdict.reply())
                .as("带着出处的两句要原样留下，且 [1][2] 不能被一起清掉")
                .isEqualTo("每日推荐摄入量为 400IU [1]。可耐受最高摄入量为 2000IU [2]。");
        assertThat(verdict.cited()).isEqualTo(2);
        assertThat(verdict.sentences()).isEqualTo(3);
        assertThat(verdict.ungrounded()).as("答里有有效引用，就不算「整篇无依据」").isFalse();
    }

    /**
     * 引用了本轮不存在的条目 —— 无条件错误，与「这句话该不该有引用」无关。
     * <p>
     * 摘掉编号之后如果正好露出一句没有出处的断言，那句话还要接着被剔——两件事互不替代。
     */
    @Test
    void 越界编号先摘除再按有无引用重新判断() {
        var verdict = CitationVerifier.verify("每日上限为 2000IU [1]。儿童上限为 1000IU [7]。", 1, false);

        assertThat(verdict.unsupported()).containsExactly("儿童上限为 1000IU [7]。");
        assertThat(verdict.reply())
                .as("越界的 [7] 不能留在给用户看的文本里")
                .isEqualTo("每日上限为 2000IU [1]。")
                .doesNotContain("[7]");
    }

    /**
     * 脱敏与引用判定必须分开两条路：一句里同时有合法与越界编号时，
     * 清越界的不能连合法的那个一起清——那会把一句本来有出处的话误判成编造。
     */
    @Test
    void 同一句里合法与越界编号混用只摘越界的那个() {
        var verdict = CitationVerifier.verify("每日上限为 2000IU [1][7]。", 1, false);

        assertThat(verdict.reply()).isEqualTo("每日上限为 2000IU [1]。");
        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.cited()).isEqualTo(1);
    }

    @Test
    void 寒暄与过渡句不受影响() {
        var verdict = CitationVerifier.verify("好的，我帮你查一下。每日上限为 2000IU [1]。", 1, false);

        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.reply()).isEqualTo("好的，我帮你查一下。每日上限为 2000IU [1]。");
    }

    /**
     * 订单类回答一个字都不该被删。
     * <p>
     * 「预计 3 天内送达」含数字、够长，单看判据它长得和一句编造的时效一模一样。
     * 区分它靠的不是这句话本身，而是<b>整篇有没有出现过引用</b>——
     * 一个从头到尾没标引用的回答，本来就不该按知识型回答去审。
     */
    @Test
    void 整篇没有引用的回答不剔除也不报告() {
        String reply = "你的订单 20260930123456 已于今天发出，预计 3 天内送达。";

        var verdict = CitationVerifier.verify(reply, 3, true);

        assertThat(verdict.reply()).isEqualTo(reply);
        assertThat(verdict.unsupported())
                .as("用户问的是包裹到哪了，告诉他「这句话没有引用」纯属噪音")
                .isEmpty();
        assertThat(verdict.stripped()).isFalse();
        assertThat(verdict.cited()).isZero();
    }

    /**
     * 没有引用、也<b>没有工具执行记录</b>——整篇都是模型自己写的，标成「整篇无依据」。
     * <p>
     * 这与上一个用例在结构上一模一样（都是一堆无引用的事实句），区别只在
     * 「这一轮有没有工具作依据」。判错的代价是反向的：订单类回答误标会天天打扰用户，
     * 而一段凭空生成的内容不标，用户就分不出它与有依据的回答——那正是这道闸
     * 唯一必须拦住的场景（实测里模型被问到知识库没覆盖的问题时，会先声明
     * 「不在平台支持范围内」再补一段通用知识，那段知识拦不住就白拦了）。
     * <p>
     * <b>标的是整篇，不是句子。</b>这个场景下逐句点名既报不准（判据靠数字与规范性词
     * 识别断言，追不上自然语言的表达方式——实测把「✅ 解答该商品的售后政策」这句
     * 能力陈述当成了无出处的断言），也没有意义：用户需要知道的不是哪几句有问题。
     */
    @Test
    void 无工具依据时整篇没有引用的回答标为无依据() {
        String reply = "蓝牙耳机连接手机时，先长按功能键进入配对模式，通常 3 秒后指示灯开始闪烁。";

        var verdict = CitationVerifier.verify(reply, 3, false);

        assertThat(verdict.ungrounded())
                .as("没有任何依据的回答必须被标出来")
                .isTrue();
        assertThat(verdict.unsupported())
                .as("整篇已由 ungrounded 表达，再逐句点名就是重复且报不准")
                .isEmpty();
        assertThat(verdict.stripped())
                .as("不剔除：整篇剔完只剩一个空气泡，用户连模型说了什么都看不到")
                .isFalse();
        assertThat(verdict.reply()).isEqualTo(reply);
        assertThat(verdict.cited()).isZero();
    }

    /** 反过来：有工具执行记录时，一堆无引用的事实句是正常的，不能标 */
    @Test
    void 有工具依据时无引用的回答不标为无依据() {
        String reply = "你的订单 20260930123456 已于今天发出，预计 3 天内送达。";

        assertThat(CitationVerifier.verify(reply, 3, true).ungrounded())
                .as("订单类回答的事实来自工具返回，标成「无依据」是误伤")
                .isFalse();
    }

    /**
     * 一个标了引用却大半没标的回答，更像「模型整体没按格式来」而不是「恰好这几句有问题」。
     * 全砍掉比留着更糟——回答会碎成几句话。此时只报告，不动正文。
     */
    @Test
    void 违规句过多时只报告不剔除() {
        String reply = "每日上限为 2000IU [1]。"
                + "第一句编造的内容为 100 天。第二句编造的内容为 200 天。第三句编造的内容为 300 天。"
                + "第四句编造的内容为 400 天。第五句编造的内容为 500 天。"
                + "第六句是正常的收尾。";

        var verdict = CitationVerifier.verify(reply, 1, false);

        assertThat(verdict.stripped()).isFalse();
        assertThat(verdict.unsupported()).hasSize(5);
        assertThat(verdict.reply())
                .as("放弃剔除时正文必须逐字不动")
                .isEqualTo(reply);
    }

    @Test
    void 违规句在预算之内时按句剔除并清理残留排版() {
        String reply = "每日上限为 2000IU [1]。\n\n- 编造的第一条为 100 天。\n- 编造的第二条为 200 天。";

        var verdict = CitationVerifier.verify(reply, 1, false);

        assertThat(verdict.stripped()).isTrue();
        assertThat(verdict.unsupported())
                .containsExactly("- 编造的第一条为 100 天。", "- 编造的第二条为 200 天。");
        assertThat(verdict.reply())
                .as("整句删掉后，它前面的列表符号和留下的空行都要一并清掉")
                .isEqualTo("每日上限为 2000IU [1]。");
    }

    /**
     * 只摘编号、不删句的情形：摘完会留下一个悬空的空格。
     * <p>
     * {@code [7]} 前面那个空格是给角标留的，角标没了它就只是排版垃圾。
     */
    @Test
    void 摘除越界编号后清理标点前的空格() {
        var verdict = CitationVerifier.verify("每日上限为 2000IU [7]。", 1, false);

        assertThat(verdict.reply()).isEqualTo("每日上限为 2000IU。");
    }

    @Test
    void 没有证据时不误伤任何内容() {
        String reply = "这是一段与知识库无关的普通回答。";

        var verdict = CitationVerifier.verify(reply, 0, true);

        assertThat(verdict.reply()).isEqualTo(reply);
        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.stripped()).isFalse();
    }

    @Test
    void 空回答不报错() {
        assertThat(CitationVerifier.verify(null, 3, true).reply()).isNull();
        assertThat(CitationVerifier.verify("  ", 3, true).unsupported()).isEmpty();
        assertThat(CitationVerifier.verify("", 3, true).sentences()).isZero();
    }

    @Test
    void 覆盖率按含有效引用的句数计算() {
        var verdict = CitationVerifier.verify("上限为 2000IU [1]。推荐量为 400IU。", 1, false);

        assertThat(verdict.sentences()).isEqualTo(2);
        assertThat(verdict.cited()).isEqualTo(1);
        assertThat(verdict.coverage()).isEqualTo(0.5);
        assertThat(CitationVerifier.verify("", 1, false).coverage())
                .as("无话可说时不该报出 0 覆盖率")
                .isEqualTo(1.0);
    }
}
