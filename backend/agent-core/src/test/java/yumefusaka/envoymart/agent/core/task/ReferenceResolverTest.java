package yumefusaka.envoymart.agent.core.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨轮序号指代的契约。
 * <p>
 * 这里钉的是一件事：<b>「第二个」必须由代码解析成确定的那一条，而不是让模型猜。</b>
 * 猜错的下一步是不可逆的加购/下单，所以判断依据不能是概率。
 */
class ReferenceResolverTest {

    private static final java.util.List<String> CANDIDATES =
            java.util.List.of("乳清蛋白粉（肌力方）", "植物蛋白粉（肌力方）", "分离乳清蛋白粉");

    @Test
    void 中文序号被补成商品名() {
        String resolved = ReferenceResolver.resolve("把第二个加进购物车", CANDIDATES);

        assertThat(resolved)
                .contains("植物蛋白粉")
                .as("原文的说法要保留给用户看，只在后面补上所指")
                .contains("第二个");
    }

    /**
     * 本类最容易踩的一处：补进去的必须是**核心名**，不能带上括号注释。
     * <p>
     * 实测失效：候选条目是「植物蛋白粉（肌力方，豌豆+糙米双蛋白）」，整条补进句子后
     * 模型把整句当 query 传给 product_search，搜出 0 条 → rawData 为空 →
     * {@code $0.skuId} 解析不到 → 报给用户的是「skuId 必须是数字」。
     * 与真实成因隔了三层，从用户那一头根本查不出来。
     */
    @Test
    void 补进去的是核心名而不是整条带注释的条目() {
        String resolved = ReferenceResolver.resolve("把第二个加进购物车",
                java.util.List.of("乳清蛋白粉（肌力方）", "植物蛋白粉（肌力方，豌豆+糙米双蛋白）"));

        assertThat(resolved)
                .contains("植物蛋白粉")
                .doesNotContain("豌豆")
                .doesNotContain("肌力方");
    }

    @Test
    void 核心名去掉嵌套括号() {
        assertThat(ReferenceResolver.coreName("鱼油软胶囊（本草纪（进口））")).isEqualTo("鱼油软胶囊");
        assertThat(ReferenceResolver.coreName("维生素 C 咀嚼片")).isEqualTo("维生素 C 咀嚼片");
    }

    @Test
    void 阿拉伯数字与不同量词都认() {
        assertThat(ReferenceResolver.resolve("第一个多少钱", CANDIDATES)).contains("乳清蛋白粉");
        assertThat(ReferenceResolver.resolve("第3款呢", CANDIDATES)).contains("分离乳清蛋白粉");
        assertThat(ReferenceResolver.resolve("第 1 件", CANDIDATES)).contains("乳清蛋白粉");
    }

    /** 越界时原样返回：正确行为是让模型去澄清「只有三款」，而不是挑一个最接近的。 */
    @Test
    void 序号越界不补() {
        assertThat(ReferenceResolver.resolve("把第五个加进去", CANDIDATES))
                .isEqualTo("把第五个加进去");
    }

    @Test
    void 没有候选时不补() {
        assertThat(ReferenceResolver.resolve("把第二个加进去", java.util.List.of()))
                .isEqualTo("把第二个加进去");
    }

    @Test
    void 没有序号时原样返回() {
        assertThat(ReferenceResolver.resolve("这个多少钱", CANDIDATES)).isEqualTo("这个多少钱");
    }

    @Test
    void 时间说法不被当成商品序号() {
        assertThat(ReferenceResolver.resolve("第三天的量是多少", CANDIDATES))
                .as("「第三天」不是商品序号，认成序号会给用户补一个莫名其妙的商品名")
                .isEqualTo("第三天的量是多少");
    }

    @Test
    void 从回答正文里认出候选商品名并保序() {
        String reply = """
                帮你找到 3 款蛋白粉：

                **1. 乳清蛋白粉（肌力方）** —— 想增肌选它
                **2. 植物蛋白粉（肌力方）** —— 素食人群选它
                **3. 分离乳清蛋白粉**：吸收更快
                """;

        java.util.List<String> items = ReferenceResolver.candidatesOf(reply);

        assertThat(items).containsExactly("乳清蛋白粉", "植物蛋白粉", "分离乳清蛋白粉");
    }

    /**
     * 澄清追问里的选项也是加粗的，但它们是**问题的一部分**，不是商品候选。
     * <p>
     * 实测失效：只认加粗会把「A. 运动增肌 / 日常补充蛋白质」「B. 素食…」当成两款商品，
     * 于是「把第二个加进购物车」被补成「第二个（A. 运动增肌…）」，
     * 模型拿整句去搜商品 → 0 条 → {@code $0.skuId} 解析不到 → 用户看到
     * 「skuId 必须是数字」。这三层里没有一层看起来是坏的。
     */
    @Test
    void 澄清选项不被当成商品候选() {
        String reply = """
                先跟您确认一下：**您主要是想解决哪种情况？**
                - **A. 运动增肌 / 日常补充蛋白质**（健身、练后补充）
                - **B. 素食，或者喝牛奶会不舒服**——这类适合植物蛋白
                """;

        assertThat(ReferenceResolver.candidatesOf(reply))
                .as("没有阿拉伯序号的加粗条目是强调或选项，不是「第几个」的所指")
                .isEmpty();
    }

    @Test
    void 空回答不产出候选() {
        assertThat(ReferenceResolver.candidatesOf(null)).isEmpty();
        assertThat(ReferenceResolver.candidatesOf("")).isEmpty();
    }
}
