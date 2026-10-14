package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计数词对账 —— 发现 P（N1 文档 §2.13）。
 *
 * <p>正例逐字取自实测（{@code u1003-1791680700402}：说「三档」却只列了两条）。
 * 反例更重要：这条改动<b>会改模型写的话</b>，判据一旦放宽就会把正确的话改错，
 * 而且改完读起来同样通顺——用户与我们都看不出来发生过什么。
 */
class CountReconciliationTest {

    @Test
    void 说三档只列两条时计数词改为两条() {
        String reply = "- SKU1（400IU×90 粒，瓶装）59.00 元\n"
                + "- SKU2（400IU×180 粒，瓶装）99.00 元\n"
                + "这三档都在你 200 元以内的预算里。\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("剔除一条后计数词没跟着改，用户看到的是「三档」配两条——一句自相矛盾的话")
                .contains("这两档都在你 200 元以内的预算里")
                .doesNotContain("这三档");
    }

    @Test
    void 数对得上时一个字都不动() {
        String reply = "- 甲\n- 乙\n- 丙\n这三款都支持七天无理由退货。\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("对得上的时候改它，就是把正确的话改错")
                .isEqualTo(reply);
    }

    @Test
    void 计数词是定语而非主语时不改() {
        String reply = "- 甲\n- 乙\n三种支付方式都可以用。\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("「三种」数的是支付方式、不是列表行数；判据缺了它就会把一句正确的话改错，"
                        + "而且改完同样通顺——这种错没有任何下游能发现")
                .isEqualTo(reply);
    }

    @Test
    void 两个列表块时不猜配对() {
        String reply = "- 甲\n- 乙\n\n补充说明：\n\n- 丙\n- 丁\n- 戊\n这两组都可以。\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("两个块与计数词的配对关系不确定，猜错会把一处错误换成另一处错误")
                .isEqualTo(reply);
    }

    @Test
    void 列表之前的计数词不动() {
        String reply = "下面三种都值得考虑：\n- 甲\n- 乙\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("块之前的数指代对象可能在别处（也可能只是承接上文），没有判据就不要改")
                .isEqualTo(reply);
    }

    @Test
    void 阿拉伯数字与中文数字各按原写法渲染() {
        assertThat(CountReconciliation.reconcile("- 甲\n- 乙\n这 3 款都有货。\n"))
                .contains("这 2 款都有货");
        assertThat(CountReconciliation.reconcile("- 甲\n- 乙\n这3款都有货。\n"))
                .contains("这2款都有货");
    }

    @Test
    void 没有列表时整篇原样返回() {
        for (String reply : List.of("这三种情况都很少见。", "你好，有什么可以帮你？", "")) {
            assertThat(CountReconciliation.reconcile(reply))
                    .as("没有可数的列表就没有对账的依据：%s", reply)
                    .isEqualTo(reply);
        }
    }

    @Test
    void 读不出的数不动() {
        String reply = "- 甲\n- 乙\n这十几款都有货。\n";

        assertThat(CountReconciliation.reconcile(reply))
                .as("读不出确切数就不猜——猜出来的数写进正文，比原来的模糊说法更糟")
                .isEqualTo(reply);
    }
}