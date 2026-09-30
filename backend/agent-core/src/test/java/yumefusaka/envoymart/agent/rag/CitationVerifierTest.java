package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    // ==================== 工具检索链路的《文档名》出处 ====================

    /**
     * 工具检索到的知识用《文档名》标出处，必须被认成有效引用。
     * <p>
     * 这些句子带数字、够长，长得和「编造的断言」一模一样——不认《》写法，
     * 它们会被逐句剔除，而内容其实有真出处，只是出处不是编号。
     */
    @Test
    void 平台声明过的标题作为出处被采信() {
        var titles = java.util.Set.of("深海鱼油说明书");
        var verdict = CitationVerifier.verify(
                "深海鱼油与华法林合用可能增加出血风险（《深海鱼油说明书》）。上限为 2000IU [1]。",
                1, true, titles);

        assertThat(verdict.cited()).isEqualTo(2);
        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.stripped()).isFalse();
    }

    /** 反例：模型编的《XX 规范》不在平台声明过的标题里，采信它就等于给编造句洗出处 */
    @Test
    void 未声明过的标题不算出处() {
        var titles = java.util.Set.of("深海鱼油说明书");
        var verdict = CitationVerifier.verify(
                "依据《平台营养补充剂管理规范》，每日摄入不得超过 5000IU。上限为 2000IU [1]。",
                1, true, titles);

        assertThat(verdict.unsupported()).containsExactly("依据《平台营养补充剂管理规范》，每日摄入不得超过 5000IU。");
        assertThat(verdict.stripped()).isTrue();
    }

    /**
     * 旧的三参调用不认《》——没传标题集合时识别面为零，行为与改造前逐字一致。
     * <p>
     * 用例句必须自带事实信号（数字或规范性词），否则它本来就进不了判据，
     * 测出来的「未剔除」是假通过：证明不了《》被忽略，只证明了这句话没被当成断言。
     */
    @Test
    void 不传标题集合时书名号不被识别() {
        var verdict = CitationVerifier.verify(
                "《深海鱼油说明书》规定每日不得超过 3000mg。上限为 2000IU [1]。", 1, true);

        assertThat(verdict.unsupported())
                .containsExactly("《深海鱼油说明书》规定每日不得超过 3000mg。");
    }

    /** 标题比对忽略空白：模型会漏掉「维生素 D3」里的空格 */
    @Test
    void 标题比对忽略空白差异() {
        var titles = new java.util.LinkedHashSet<String>();
        CitationVerifier.collectTitles(titles, "维生素 D3 说明书", "《维生素 D3 说明书》 > 第二章");

        var verdict = CitationVerifier.verify(
                "每日上限为 4000IU（《维生素D3说明书》）。", 0, true, titles);

        assertThat(verdict.unsupported()).isEmpty();
    }

    /**
     * 工具输出的标题只从「出处：」段采信。
     * <p>
     * 全篇扫描会把检索回来的原文引用里任意书名（如法条名）都升级成平台出处——
     * 而正文里引用一段提到《消费者权益保护法》的资料，不等于平台声明了该法为出处。
     * <p>
     * <b>两组夹具都要在</b>：出处与引文<b>同行</b>是真实渲染格式（见
     * InteractionCheckTool#riskLine），只按「行含出处」扫描时它才是漏网的那一种；
     * 分行只是同一条规则的另一种排版。
     */
    @Test
    void 工具输出只从出处段采信标题() {
        var sameLine = new java.util.LinkedHashSet<String>();
        CitationVerifier.collectToolTitles(sameLine,
                "  ⚠ 慎用：与「华法林」 —— 增加出血风险\n"
                        + "      出处：《深海鱼油说明书》｜原文：「本品与《中国居民膳食指南》的建议不同。」\n");

        var wrapped = new java.util.LinkedHashSet<String>();
        CitationVerifier.collectToolTitles(wrapped,
                "[片段 1] 出处：《深海鱼油说明书》 > 第三章\n"
                        + "原文：本品的服用请参照《中国居民膳食指南》相关建议。\n");

        assertThat(sameLine).as("引文里的书名不能跟着同行出处一起被采信").containsExactly("深海鱼油说明书");
        assertThat(wrapped).containsExactly("深海鱼油说明书");
    }

    /** 切片标题本身不带书名号时也要收——位置串里带《》的形态同样收 */
    @Test
    void 证据标题与位置串两种形态都收集() {
        var titles = new java.util.LinkedHashSet<String>();
        CitationVerifier.collectTitles(titles, "深海鱼油说明书", null);
        CitationVerifier.collectTitles(titles, null, "《维生素 D3 说明书》 > 第二章 > 3.2");

        assertThat(titles).containsExactlyInAnyOrder("深海鱼油说明书", "维生素D3说明书");
    }

    /**
     * 拒答回答的两句骨架不该被当成「讲事实却没出处」。
     * <p>
     * 实测样本（问知识库里没有的类目时的真实回答）：模型先说「你问的是…的规定」，
     * 再说「知识库中未检索到…条款」——两句都被判违规并删掉，剩下的规则罗列
     * 反而读不出结论。它们的依据分别是「用户刚说过的话」和「检索结果为空」，
     * 两者都没有可标的出处，也不该有。
     */
    @Test
    void 复述问题与说明检索为空的句子不要求引用() {
        String reply = "你问的是平台对宠物食品召回政策的规定。当前知识库中未检索到相关条款。"
                + "平台退换货政策总则适用于食品类商品 [1]。";

        var verdict = CitationVerifier.verify(reply, 1, false);

        assertThat(verdict.unsupported()).isEmpty();
        assertThat(verdict.reply()).as("两句骨架原样保留").isEqualTo(reply);
    }

    /**
     * 上一条的反向边界：豁免必须窄到只放过「不存在」。
     * <p>
     * 「不」是否定谓词，句子的命题仍然是一条事实——「本品不适用于孕妇」若被豁免，
     * 模型写「本品不适用于孕妇，每日摄入不得超过 1000IU」就再没人拦了。
     */
    @Test
    void 否定谓词的事实断言仍然要求引用() {
        var verdict = CitationVerifier.verify(
                "本品不适用于孕妇，每日摄入不得超过 1000IU。每日推荐摄入量为 400IU [1]。", 1, false);

        assertThat(verdict.unsupported()).containsExactly("本品不适用于孕妇，每日摄入不得超过 1000IU。");
    }

    /**
     * 剔除会留下「孤儿标题」：标题行本身不含断言，永远不违规，但它唯一的正文被抠掉了，
     * 用户看到的是一个标题下面直接跟着另一个标题。留着它比留着那句没出处的话更糟——
     * 读者会以为平台"确实有这么一段注意事项"，只是内容没渲染出来。
     */
    @Test
    void 剔除之后不留下面空着的标题() {
        String reply = "平台规则如下 [1]。\n\n⚠️ 注意：\n"
                + "- 平台规定宠物食品保质期不得少于 12 个月。\n\n✅ 建议：\n- 查看商品页标注的保质期。";

        var verdict = CitationVerifier.verify(reply, 1, false);

        assertThat(verdict.stripped()).isTrue();
        assertThat(verdict.reply())
                .doesNotContain("⚠️ 注意")
                .as("下面有内容的标题不能跟着一起被删")
                .contains("✅ 建议：")
                .contains("查看商品页标注的保质期");
    }

    // ==================== 《文档名》→[n] 归一化 ====================
    // 模型手上有两套出处写法，而它按文档分、不按来源分：同一个文档在 prompt 证据与
    // 工具结果里都出现时，它会把整篇统一写成书名号。编号是界面上点回原文的唯一入口，
    // 提示词两侧都写了「各管各的」，但提示词是请求不是保证——这里是那个保证。

    @Test
    void 指得出证据的文档名被换回编号() {
        String reply = CitationVerifier.numberTitles(
                "每日上限为 2000IU（《维生素 D3 说明书》）。与华法林同服需谨慎（《药物相互作用手册》）。",
                List.of(chunk("维生素D3说明书", "《维生素 D3 说明书》 > 第二章 > 3.2"),
                        chunk("药物相互作用手册", null)));

        assertThat(reply)
                .as("标题只在空白上不同也要认；位置串里的标题与标题字段是同一个出处")
                .isEqualTo("每日上限为 2000IU（[1]）。与华法林同服需谨慎（[2]）。");
    }

    @Test
    void 不在证据里的文档名原样保留() {
        String reply = CitationVerifier.numberTitles(
                "平台对宠物食品的规定见（《宠物用品类目管理规范》）。", List.of(chunk("维生素D3说明书", null)));

        assertThat(reply)
                .as("平台从没声明过的书名不是证据，不能凭空给它一个编号")
                .isEqualTo("平台对宠物食品的规定见（《宠物用品类目管理规范》）。");
    }

    /**
     * 同一份文档切成多片是常态（本项目 16 篇 / 132 片）。
     * <p>
     * 取最靠前的那条：模型写《文档名》时本来就没指明是哪个切片，指向该文档最相关
     * ——即排在前面——的那条，不比一个不能点的书名号更错，而它让用户有得点。
     */
    @Test
    void 同一文档多切片指向最靠前的那一条() {
        String reply = CitationVerifier.numberTitles(
                "上限为 2000IU（《维生素D3说明书》）。",
                List.of(chunk("其他文档", null),
                        chunk("维生素D3说明书", null),
                        chunk("维生素D3说明书", null)));

        assertThat(reply).isEqualTo("上限为 2000IU（[2]）。");
    }

    private static DocumentChunk chunk(String title, String position) {
        return DocumentChunk.builder()
                .chunkId(title + "#" + position)
                .title(title)
                .position(position)
                .build();
    }
}
