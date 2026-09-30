package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolCallResponse {

    private String tool;
    private String input;
    private String output;
    /**
     * 这次调用成没成。
     * <p>
     * <b>不给这个字段，前端就只能靠读 output 的文本猜。</b>失败的输出是一句
     * 「工具执行失败：…」，成功但没查到是一句「没有找到…」，都混在同一个纯文本字段里，
     * 界面上只能一律按中性渲染。带上它，失败才画得出红、才不必让用户从散文里辨认结局。
     */
    private boolean success;
    /**
     * 跑通了但什么都没查到——第三态，见 {@code ToolResult#noData}。
     * <p>
     * 二态渲染的后果很具体：查一个不存在的订单，工具确实跑通了，绿标亮「成功」，
     * 而同一张卡片上的回答写着「没查到物流信息」。用户看到自相矛盾的两句话。
     */
    private boolean noData;
    /** 墙钟耗时（毫秒） */
    private long latencyMs;

    /**
     * 这次调用<b>确立的业务事实</b>，形如 {@code 订单状态 → 已支付}、{@code 应付金额 → ¥128.00}。
     * <p>
     * <b>它下发给界面是为了让「核对过了」这件事看得见。</b>回答里那个金额和后端握着的
     * 事实一致——这个结论此前只存在于日志里，用户和面试官都只能选择相信。摆在这一栏，
     * 「机器可核对」就从一句话变成一眼能对上的两列。
     * <p>
     * 为空表示这次工具没有声明可机器比对的事实（知识检索、物流轨迹这类本来就没有），
     * 不代表核对失败。
     */
    private java.util.Map<String, String> facts;
}
