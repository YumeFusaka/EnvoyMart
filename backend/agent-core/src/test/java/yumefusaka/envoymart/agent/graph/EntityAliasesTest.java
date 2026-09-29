package yumefusaka.envoymart.agent.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 别名表的契约。表本身是手写数据，测试盯的是<b>三个调用方依赖的性质</b>：
 * 键必须规范化、类型以表为准、查不到返回 null。
 * <p>
 * 三条各对应一个静默失败：键没规范化则表形同不存在；类型不纠正则同一个东西
 * 在两篇文档里类型不一致；查不到不返回 null 则调用方无法区分「表里有」和「表里没有」，
 * 于是要么把默认值当结论，要么被迫再查一次。
 */
class EntityAliasesTest {

    @Test
    void 变体解析到规范名与规范类型() {
        EntityAliases.Canonical hit = EntityAliases.resolve("富马酸亚铁");

        assertThat(hit).isNotNull();
        assertThat(hit.name()).isEqualTo("铁剂");
        assertThat(hit.kind()).isEqualTo(EntityKind.INGREDIENT);
    }

    @Test
    void 规范名自己也是一个变体() {
        // 「铁剂」既是规范名，也常常直接出现在文档里。只登记别名不登记规范名的话，
        // 用户在问题里说「铁剂」反而解析不到
        assertThat(EntityAliases.resolve("铁剂")).isNotNull();
        assertThat(EntityAliases.variantsOf("铁剂")).contains("铁剂", "富马酸亚铁", "硫酸亚铁");
    }

    @Test
    void 类型以表为准_不采信调用方给的那个() {
        // 实测中模型把同一类药物一会儿标 DRUG 一会儿标 DRUG_CLASS。
        // 类型不归规范的话，节点上留哪一个取决于哪篇文档后写入
        assertThat(EntityAliases.resolve("强心苷类药物").kind()).isEqualTo(EntityKind.DRUG_CLASS);
        assertThat(EntityAliases.resolve("强心苷类").kind()).isEqualTo(EntityKind.DRUG_CLASS);
        assertThat(EntityAliases.resolve("抗生素").kind()).isEqualTo(EntityKind.DRUG_CLASS);
    }

    @Test
    void 带空格的写法也能命中() {
        // 键是规范化后的产物。注册时若按原始写法当键（「鱼油中的 EPA 与 DHA」带空格），
        // 查询侧拿规范化后的写法来找就永远找不到——而症状与「表里没这条」完全一样
        assertThat(EntityAliases.resolve(EntityNames.normalize("鱼油中的 EPA 与 DHA")))
                .isNotNull()
                .extracting(EntityAliases.Canonical::name)
                .isEqualTo("深海鱼油");
    }

    @Test
    void 表里没有的写法返回null() {
        // 返回 null 而不是「原样包一层」：调用方要靠这个 null 决定沿用原名原类型。
        // 两者都返回对象的话，「命中」与「没命中」在调用方看来一模一样
        assertThat(EntityAliases.resolve("叶酸")).isNull();
        assertThat(EntityAliases.resolve("维生素d3")).isNull();
        assertThat(EntityAliases.resolve(null)).isNull();
        assertThat(EntityAliases.resolve("")).isNull();
    }

    @Test
    void 变体是拷贝_改不动表() {
        assertThat(EntityAliases.variantsOf("铁剂")).isNotSameAs(EntityAliases.variantsOf("铁剂"));
        assertThat(EntityAliases.variantsOf("没这个规范名")).containsExactly("没这个规范名");
    }

    @Test
    void 维生素D3不并入维生素D() {
        // 这一条是「有意不合并」，见 EntityAliases 文末。写成测试是为了让
        // 「有人顺手加一条别名」时先看到这里的理由，而不是先改表
        assertThat(EntityAliases.resolve("维生素d3")).isNull();
    }
}
