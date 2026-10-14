package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.llm.ToolExecution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品行豁免的契约：句中的商品字段能逐字段对上工具回执时放行，对不上就拒。
 *
 * <p>夹具取自生产现场（{@code - 候选[n] 编号 SPU..：} 是工具当前的打印格式，
 * 旧的 {@code - 编号 SPU..：} 已被淘汰——用旧格式做夹具会让解析器读不出候选块，
 * 于是正确的商品行被误判成「没有回执支撑」，那是夹具过期，不是产品缺陷）。
 *
 * <p>三条拒例分别守三件事：规格数量必须属于所引 SKU、金额必须属于所引 SKU、
 * 编号必须真实出现在回执里。放宽任何一条，用户就会看到不存在的规格或价格。
 */
class N1ProductRowExemptionContractTest {

    /** 生产现场原样回执（u1003 会话，只有 output 文本、rawData 已裁剪）。 */
    private static ToolExecution adSearch() {
        return ToolExecution.builder().tool("product_search").success(true)
                .output("找到 3 个商品：\n"
                        + "- 候选[0] 编号 SPU35：维生素 AD 滴剂（每滴含维生素 A 与 D3，婴幼儿适用），价格 59.00 元，类目 儿童营养，品牌 元气元素，有货，标签 儿童、维生素A、维生素D\n"
                        + "    规格：SKU46 59.00 元 有货\n"
                        + "- 候选[1] 编号 SPU29：维生素 C 咀嚼片（每片 500mg，橙味），价格 39.00 元-59.00 元，类目 维生素矿物质，品牌 元气元素，有货，标签 维生素C、免疫\n"
                        + "    规格：SKU38（规格:60 片） 39.00 元 有货；SKU39（规格:120 片） 59.00 元 有货。该商品有多个规格，加入购物车前必须让用户明确选择规格，不得默认选择\n"
                        + "- 候选[2] 编号 SPU20：维生素 C 咀嚼片（免疫配方）（每片 500mg，橙味），价格 49.00 元，类目 免疫提升，品牌 元气元素，有货，已售 2341 件，标签 免疫\n"
                        + "    规格：SKU29 49.00 元 有货\n")
                .build();
    }

    @Test
    void 描述行与规格行都能由回执放行() {
        var tools = List.of(adSearch());
        for (String row : List.of(
                // 不带编号的描述行：靠「整句 token 与金额都能在本块找到」兜底
                "每滴含维生素 A 与 D3，婴幼儿适用 [2]。",
                "- 规格：SKU38，60 片，39.00 元，有货 [2]",
                "- 规格：SKU38（规格:60 片），39.00 元，有货 [2]")) {
            assertThat(MedicalEvidenceVerifier.verify(true, row, List.of(), tools,
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported())
                    .as(row).isEmpty();
        }
    }

    @Test
    void 规格数量或金额不属于所引SKU时必须拒() {
        var tools = List.of(adSearch());
        for (String row : List.of(
                // 120 片属于 SKU39，不属于句中所引的 SKU38
                "- 规格：SKU38，120 片，39.00 元，有货 [2]",
                // 59.00 元属于 SKU39，不属于句中所引的 SKU38
                "- 规格：SKU38，60 片，59.00 元，有货 [2]",
                // 回执里根本没有 SKU99
                "- 规格：SKU99，60 片，39.00 元，有货 [2]")) {
            assertThat(MedicalEvidenceVerifier.verify(true, row, List.of(), tools,
                    EvidenceGate.Thresholds.defaults(), java.util.Set.of()).unsupported())
                    .as(row).hasSize(1);
        }
    }
}