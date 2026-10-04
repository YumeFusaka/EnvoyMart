package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleRequest;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleResult;

import java.util.Map;
import java.util.Set;

/**
 * 提交售后申请工具 —— 用户说「我要退货」时真正把申请交上去。
 * <p>
 * <b>与 {@code after_sale_preview} 的分工：先算、再交。</b>预览只回答「够不够条件、最多退多少」，
 * 不改任何状态；本工具才落一张工单。两者分开是因为用户在下决心前需要的是条件，
 * 而条件判断有政策引擎兜底——把它和「提交」合成一步，用户就会被一张已提交的工单推着走。
 * <p>
 * <b>类型白名单写在参数说明里，不在这里校验。</b>模型从说明里选一个词，选错了由下游拒绝；
 * 工具层再拦一道只会把下游的原文（「该订单不支持换货」这类）换成一句更笼统的话，
 * 而那句话恰恰是用户最需要看清的。
 */
@Slf4j
public class AfterSaleTool implements Tool {

    /** 交易域认的三个类型。列在这里是为了让「说明里写得出、代码里查得到」是同一份 */
    private static final Set<String> TYPES = Set.of("REFUND", "RETURN", "EXCHANGE");

    private final OrderClient orderClient;

    public AfterSaleTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("after_sale_apply")
                .description("为某个订单行提交售后申请（退款 / 退货 / 换货）。"
                        + "用户说「我要退货」「申请退款」「这个坏了要换」时使用。"
                        + "**调用前先用 after_sale_preview 确认这笔订单行符不符合条件**——"
                        + "不符合时不要提交，把预览给出的原因告诉用户。"
                        + "orderItemId 是订单条目编号（订单详情里每一行一个），不是订单编号。")
                .requiresConfirmation(true)
                .parameters(Map.of(
                        "orderItemId", ToolDefinition.ParameterSpec.builder()
                                .type("integer")
                                .description("订单条目编号（订单详情里每一行对应一个 orderItemId）")
                                .required(true).build(),
                        "type", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("售后类型，只能取三个值之一：REFUND（仅退款）、"
                                        + "RETURN（退货退款）、EXCHANGE（换货）。"
                                        + "用户说「退了不要了」取 RETURN，说「只退钱」取 REFUND，"
                                        + "说「换一个」取 EXCHANGE")
                                .required(true).build(),
                        "reason", ToolDefinition.ParameterSpec.builder()
                                .type("string")
                                .description("申请原因，简短一句，例如「不想要了」「收到时已破损」")
                                .required(false).build(),
                        "qualityIssue", ToolDefinition.ParameterSpec.builder()
                                .type("boolean")
                                .description("是否属于质量问题。破损、变质、与描述不符取 true；"
                                        + "单纯不想要取 false。不传按 false 处理")
                                .required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            AgentAfterSaleRequest request = new AgentAfterSaleRequest();
            request.setOrderItemId(Long.valueOf(String.valueOf(call.getArguments().get("orderItemId"))));
            request.setType(normalizeType(call));
            Object reason = call.getArguments().get("reason");
            request.setReason(reason == null ? null : String.valueOf(reason).strip());
            request.setQualityIssue(qualityIssueOf(call));

            AgentAfterSaleResult result =
                    Downstream.mutate("售后", () -> orderClient.applyAfterSale(userId, request));
            if (result == null) {
                return ToolResult.builder().success(false)
                        .output("售后申请没有提交成功，请让用户稍后再试。")
                        .build();
            }
            StringBuilder sb = new StringBuilder("售后申请已提交，单号 ").append(result.getAfterSaleNo());
            if (result.getStatusText() != null) {
                sb.append("，当前状态：").append(result.getStatusText());
            }
            if (result.getRefundAmount() != null) {
                sb.append("，预计退款 ").append(Money.yuan(result.getRefundAmount()));
            }
            if (result.getDocRef() != null) {
                sb.append("。判据见 ").append(result.getDocRef());
            }
            return ToolResult.builder()
                    .success(true).output(sb.toString()).rawData(result).build();
        } catch (Exception e) {
            return Downstream.failure("售后申请", e);
        }
    }

    /**
     * 类型统一成大写再比对白名单。
     * <p>
     * 模型偶尔会把 {@code RETURN} 写成 {@code return}，大小写不该成为一次申请失败的
     * 原因；但**白名单本身要拦**——编出来的类型必须当场失败，
     * 否则它会一路走到下游、回来的错误信息里没有「你写错了类型」这层意思。
     */
    private String normalizeType(ToolCall call) {
        Object raw = call.getArguments().get("type");
        String type = raw == null ? "" : String.valueOf(raw).strip().toUpperCase(java.util.Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("售后类型只能是 REFUND / RETURN / EXCHANGE，收到的是「" + raw + "」");
        }
        return type;
    }

    private boolean qualityIssueOf(ToolCall call) {
        Object raw = call.getArguments().get("qualityIssue");
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        return Boolean.parseBoolean(String.valueOf(raw).strip());
    }
}
