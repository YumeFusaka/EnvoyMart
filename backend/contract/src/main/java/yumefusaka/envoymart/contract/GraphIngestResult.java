package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱写入结果 —— <b>由 knowledge-service 发出，ai-service 消费</b>。
 * <p>
 * 回的不只是「成功/失败」，而是三个分开的数字：抽了多少条、入库多少条、丢了多少条。
 * 合并成一个布尔值的话，「入库 0 条」既可能是这篇文档确实没有可用关系，
 * 也可能是词表或引文校验把全部候选都拒了——后者说明抽取提示词需要改，
 * 而前者什么都不用做。两者的界面表现完全一样。
 *
 * @param available 图谱是否可用。false 时 {@code stored} 必为 0，
 *                  调用方必须把「没建成」说出去，而不是当成「建好了但没内容」
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphIngestResult {

    private String docNo;
    /** 通过校验并写入的关系条数 */
    private int accepted;
    /** 被丢弃的候选条数（词表不合规，或引文在原文里找不到） */
    private int rejected;
    /** 真正落到图上的条数。图谱不可用时为 0 */
    private int stored;
    private boolean available;
}
