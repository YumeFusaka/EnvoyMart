package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.AuthClient;
import yumefusaka.envoymart.aiservice.model.AgentAddress;

import java.util.List;
import java.util.Map;

/**
 * 收货地址查询 —— 只读，但补的是「下单」这条链路缺的一环。
 * <p>
 * <b>为什么要查、又为什么只查不让选。</b>实测里有一句很能说明问题的回答：用户说
 * 「帮我把购物车结算掉，用默认地址」，Agent 回了一串「请提供收货人、手机号、省市区」——
 * 它并非不愿做，而是<b>手上根本没有这份数据</b>。地址在 auth-service 的地址簿里，
 * 而 Agent 只连了 order / product / knowledge。
 * <p>
 * 但「能读到」不等于「替用户决定」。{@link CheckoutTool} 坚持地址由用户提供是对的：
 * 默认地址是用户的偏好，不是事实，这次可能就想寄到公司。所以本工具的正确用法是
 * <b>把读到的默认地址念给用户听，让他确认</b>——把「请你自己打一遍地址」变成
 * 「寄到 XX，对吗」。一句话的差别，是「能查」与「能用」的差别。
 * <p>
 * 读不到时明确回「没有存过地址」，让模型去问用户，而不是让它编一个地址出来。
 */
@Slf4j
public class AddressTool implements Tool {

    private final AuthClient authClient;

    public AddressTool(AuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("address_list")
                .description("查询当前用户收货地址簿里已保存的地址（含默认地址）。"
                        + "当用户要下单/结算但没在话里给出收货信息时使用："
                        + "先调本工具把默认地址念给用户确认，"
                        + "**不要自己编一个地址，也不要凭空报出一个不存在的地址**。"
                        + "「先不要用默认地址」「还没确定寄哪里」不豁免本工具——"
                        + "用户拒绝的只是**默认**那一条，地址簿里可能还有他想要的（比如公司地址），"
                        + "此时先查出来让他挑，不许跳过查询直接向他索要。"
                        + "只有两种情况不念默认地址：地址簿为空；或用户给出了地址簿之外的新地址，则向他索要完整收货信息。"
                        + "**本工具只读**：不提供新增、修改、删除地址的能力。")
                .requiresConfirmation(false)
                .parameters(Map.of())
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            List<AgentAddress> addresses = Downstream.read("收货地址", () -> authClient.listAddresses(userId));
            if (addresses == null || addresses.isEmpty()) {
                return ToolResult.builder().success(true)
                        .output("这个账号的地址簿是空的，没有可用的收货地址。"
                                + "请向用户索要完整的收货信息：收货人、手机号、省、市、区、详细地址。")
                        .build();
            }
            AgentAddress preferred = pickDefault(addresses);
            StringBuilder sb = new StringBuilder();
            sb.append("地址簿共 ").append(addresses.size()).append(" 条。");
            if (preferred != null) {
                sb.append("默认地址：\n").append(formatBlock(preferred));
                sb.append("\n请把这条地址念给用户确认——确认后再调用 cart_checkout。");
                // 非默认地址必须一并念出来：用户说「不要默认那条」（如寄公司）时，
                // 模型手里只有其余条目的完整内容，才谈得上描述里承诺的「念给他挑」。
                List<AgentAddress> others = addresses.stream().filter((a) -> a != preferred).toList();
                if (!others.isEmpty()) {
                    sb.append("\n其余 ").append(others.size()).append(" 条：");
                    for (AgentAddress a : others) {
                        sb.append("\n").append(formatBlock(a));
                    }
                    sb.append("\n用户说不要默认地址、或要寄到别处时，把这些条目念给他挑，"
                            + "并把挑中的条目按字段填进 cart_checkout，不要重新索要地址簿里已有的信息。");
                }
            } else {
                sb.append("没有标记为默认的地址，可选项：");
                for (AgentAddress a : addresses) {
                    sb.append("\n").append(formatBlock(a));
                }
                sb.append("\n请让用户指定其中一条，不要替用户挑。");
            }
            return ToolResult.builder().success(true).output(sb.toString()).rawData(preferred != null ? preferred : addresses).build();
        } catch (Exception e) {
            return Downstream.failure("查询收货地址", e);
        }
    }

    /**
     * 优先取标记为默认的那条；没有默认时返回 null。
     * <p>
     * <b>刻意不做「没有默认就取第一条」。</b>地址簿的排序是插入顺序，与用户偏好无关；
     * 把第一条当成默认去下单，选错地址的代价是退不掉的一单。
     * 宁可多问一句「你要寄哪个」，也不赌一把。
     */
    private static AgentAddress pickDefault(List<AgentAddress> addresses) {
        for (AgentAddress a : addresses) {
            if (a.getIsDefault() != null && a.getIsDefault() == 1) {
                return a;
            }
        }
        return null;
    }

    /**
     * 逐字段输出，而不是拼成一整句。两个理由：模型要把选中条目填进 cart_checkout 的六个
     * 参数（省/市/区连排一句话拆不出来），逐字段是它唯一可靠的来源；{@code tag}（家/公司）
     * 是用户「寄公司」这类指代能否对上号的唯一凭据，此前建成后没有任何链路读过它。
     */
    private static String formatBlock(AgentAddress a) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 收货人：").append(nullSafe(a.getReceiverName()));
        sb.append("；手机号：").append(nullSafe(a.getReceiverPhone()));
        sb.append("；省：").append(nullSafe(a.getProvince()));
        sb.append("；市：").append(nullSafe(a.getCity()));
        sb.append("；区：").append(nullSafe(a.getDistrict()));
        sb.append("；详细地址：").append(nullSafe(a.getDetail()));
        if (a.getTag() != null && !a.getTag().isBlank()) {
            sb.append("；标签：").append(a.getTag());
        }
        if (a.getIsDefault() != null && a.getIsDefault() == 1) {
            sb.append("；[默认地址]");
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
