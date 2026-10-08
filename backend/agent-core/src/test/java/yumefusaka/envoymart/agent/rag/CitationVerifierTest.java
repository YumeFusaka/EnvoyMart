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
    void 语义拒绝不会因已有角标而放行() {
        String falseClaim = "成人每天服用 9000 毫克 [1]。";
        assertThat(CitationVerifier.removeRejected(falseClaim + "\n请咨询医师。", List.of(falseClaim)))
                .doesNotContain("9000").contains("请咨询医师");
        assertThat(CitationVerifier.candidates("可以叠加，但有限制。", 1,
                java.util.Set.of(), java.util.Set.of())).containsExactly("可以叠加，但有限制。");
    }

    @Test
    void 医嘱提示不能豁免具体剂量审核() {
        assertThat(CitationVerifier.candidates("成人每天服用 9000 毫克，请遵医嘱。", 1,
                java.util.Set.of(), java.util.Set.of())).containsExactly("成人每天服用 9000 毫克，请遵医嘱。");
    }

    @Test
    void 补引用保留换行且编号属于当前事实句() {
        String first = "- 旧版每片含铁 15 毫克。";
        String second = "- 新版每片含铁 10 毫克。";
        String repaired = CitationVerifier.repairCitations(first + "\n" + second + "\n",
                java.util.Map.of(first, List.of(1), second, List.of(2)));
        assertThat(repaired).isEqualTo("- 旧版每片含铁 15 毫克 [1]。\n- 新版每片含铁 10 毫克 [2]。\n");
    }

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
     * 同一份文档切成多片是常态（本项目 47 篇 / 408 片）。
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

    // ==================== 无依据横幅：寒暄 vs 平台陈述 ====================
    // 横幅的判据原先只有「没有引用、没有工具记录」，于是纯寒暄轮也挂上一条
    // 「无平台依据」的橙色警示。收紧成「至少有一句被 needsCitation 判为断言」是错的：
    // 「平台支持七天无理由退货」既没有数字也没有 FACT_SIGNAL 的词，
    // 那一改会让整段编造的政策条款不再被标注 —— 用可见但无害的提示换静默幻觉漏洞。
    // 下面两个用例就是这条边界的两侧，必须同时成立。

    @Test
    void 纯寒暄不标为无依据() {
        String[] 寒暄 = {
                "你好！有什么可以帮你的吗？",
                "不客气，随时找我。",
                "好的，我这就为你处理。",
        };

        for (String reply : 寒暄) {
            var verdict = CitationVerifier.verify(reply, 3, false);
            assertThat(verdict.ungrounded())
                    .as("「%s」没有任何关于平台的陈述，挂无依据横幅是误报", reply)
                    .isFalse();
            assertThat(verdict.reply()).isEqualTo(reply);
            assertThat(verdict.unsupported()).isEmpty();
        }
    }

    /**
     * <b>真实形态</b>：模型对「你好」不是回一句「你好」，而是回一段带能力清单的自我介绍。
     * <p>
     * 这段文本里「订单 / 物流 / 售后 / 商品」这些领域名词一个不少，第一版判据
     * （领域名词出现即算平台陈述）因此把整篇判成「无平台依据」，橙色横幅照挂——
     * 端到端实测就是这么漏的。清单描述的是<b>助手能做什么</b>，不是<b>平台有什么规则</b>。
     */
    @Test
    void 带能力清单的自我介绍不标为无依据() {
        String reply = """
                你好！我是你的电商助手，可以帮你：

                - **找商品**：按需求、价格、适用人群等帮你筛选
                - **查订单**：订单状态、金额、商品明细、收货信息
                - **查物流**：包裹到哪了、每一步的轨迹
                - **售后咨询**：退换货规则、商品说明书相关问题
                - **搭配检查**：几样东西能不能一起吃/一起用

                有什么想了解的，直接告诉我就行～""";

        // 走意图门槛这一版：用户问的是「你好」，不是平台的事
        var verdict = CitationVerifier.verify(reply, 3, false, java.util.Set.of(), "你好");

        assertThat(verdict.ungrounded())
                .as("自我介绍里的领域名词说的是助手能做什么，不是平台规则，挂横幅是误报")
                .isFalse();
        assertThat(verdict.reply()).isEqualTo(reply);
    }

    /**
     * 意图门槛不能把真正的编造放过去 —— 用户问的就是退货政策，
     * 模型给一段没有出处的政策条款，横幅必须挂。
     */
    @Test
    void 用户问了平台的事时编造的政策陈述仍然标为无依据() {
        var verdict = CitationVerifier.verify("平台支持七天无理由退货。", 3, false,
                java.util.Set.of(), "你们平台支持七天无理由退货吗？");

        assertThat(verdict.ungrounded())
                .as("问的是退货政策，答的是没有出处的政策条款，必须标出来")
                .isTrue();
    }

    /** 意图门槛本身：只有明确认出是纯寒暄才收回提示，其余一律按「问了平台的事」处理 */
    @Test
    void 意图门槛只对明确的纯寒暄放行() {
        for (String chitchat : new String[] {"你好", "您好！", "谢谢", "好的", "再见", "你能做什么？"}) {
            assertThat(CitationVerifier.asksAboutPlatform(chitchat))
                    .as("「%s」是纯寒暄，不该被当作平台提问", chitchat)
                    .isFalse();
        }
        for (String question : new String[] {
                "你们平台支持七天无理由退货吗？",
                "这个订单什么时候到？",
                "维生素 D3 每天吃多少？",
                "会员有什么权益？",
                "这个东西怎么样？",
                "维生素和钙片能一起吃吗？"}) {
            assertThat(CitationVerifier.asksAboutPlatform(question))
                    .as("「%s」在问平台/商品的事，必须按平台提问处理", question)
                    .isTrue();
        }
        // 站外话题同样不是「在问平台的事」：模型答「我查不了天气」并附能力介绍是正确行为。
        // 但必须整句没有平台领域词才算，混合提问仍按平台问题处理。
        for (String offTopic : new String[] {"今天天气怎么样？", "现在几点了？", "讲个笑话吧"}) {
            assertThat(CitationVerifier.asksAboutPlatform(offTopic))
                    .as("「%s」只能由站外知识回答，不该挂「无平台依据」", offTopic)
                    .isFalse();
        }
        assertThat(CitationVerifier.asksAboutPlatform("天气这么热，这个保健品需要冷藏吗？"))
                .as("混合提问里有平台领域词，仍按平台问题处理")
                .isTrue();
    }

    /**
     * <b>U39 的核心反例 —— 这条断言丢了，U39 就修错了。</b>
     * 建模出的政策条款既没有数字也不含 {@code FACT_SIGNAL} 的词，
     * 它必须仍然被标成「整篇无依据」，否则编造的平台政策会静默通过。
     */
    @Test
    void 没有数字没有信号词的政策陈述仍然标为无依据() {
        String[] 编造的政策 = {
                "平台支持七天无理由退货。",
                "本店所有商品均支持货到付款。",
                "会员可以享受双倍积分。",
                "该商品适合孕妇服用。",
        };

        for (String reply : 编造的政策) {
            var verdict = CitationVerifier.verify(reply, 3, false);
            assertThat(verdict.ungrounded())
                    .as("「%s」是在陈述平台的事，没有依据时必须标出来", reply)
                    .isTrue();
        }
    }

    /**
     * 规则谓词的优先级 —— 一条真正的规则断言即便戴着「帮你」的帽子，
     * 也不能被「助手自我描述」的排除规则放过去。
     */
    @Test
    void 戴着帮你帽子的政策陈述仍然标为无依据() {
        var verdict = CitationVerifier.verify("平台支持七天无理由退货，帮你省去后顾之忧。", 3, false);

        assertThat(verdict.ungrounded())
                .as("句子里有规则谓词「支持」，「帮你」不能把它变成自我介绍")
                .isTrue();
    }

    /** 反向边界：有工具依据时，同样一段政策陈述不该被标 —— 事实来自工具返回 */
    @Test
    void 有工具依据时政策陈述不标为无依据() {
        assertThat(CitationVerifier.verify("平台支持七天无理由退货。", 3, true).ungrounded())
                .as("这一轮事实来自工具返回，没有引用是正常的")
                .isFalse();
    }
}
