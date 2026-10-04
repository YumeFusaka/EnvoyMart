package yumefusaka.envoymart.aiservice.tool;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentAddCartRequest;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;

import java.util.Map;

/**
 * 加入购物车工具 —— Agent 第一次真正改变交易状态的动作。
 * <p>
 * <b>为什么它需要确认，而查询类工具不需要。</b>加购会写库：写进去之后用户的购物车里
 * 就多了一件他可能并不想要的东西，而这个动作<b>没有对应的「撤销」按钮</b>——
 * 用户得自己去购物车里找出来删掉。凡是「做错了要用户来收尾」的动作，都必须先问一次。
 * <p>
 * <b>主键选 SKU 而不是 SPU。</b>用户说「加两瓶」时，「哪一瓶」由规格决定——
 * 同一个 SPU 下不同规格可能是完全不同的价格与库存，选错规格等于下错单。
 * 搜索工具给出的结果里带 SKU 才让它成为可能，这也是为什么加购必须以
 * 「刚才搜到的那个商品」为前置：模型手上得有 skuId，而不是一个商品名。
 */
@Slf4j
public class AddToCartTool implements Tool {

    private final OrderClient orderClient;

    public AddToCartTool(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("cart_add")
                .description("把指定规格的商品加入当前用户的购物车。"
                        + "用户说「帮我加进购物车」「买两件」「要这个」时使用。"
                        + "**调用前必须先用 product_search 查到商品**，"
                        + "并从它输出的「规格：SKU<数字>」里取那个数字作为 skuId——"
                        + "本工具只认 SKU 编号。"
                        + "**绝对不要自己编一个 skuId**（比如 0）："
                        + "product_search 的输出里没有出现过的编号都是无效的，"
                        + "编出来的编号要么加错规格、要么直接失败。"
                        + "如果用户要的商品还没查过，先调用 product_search。"
                        + "数量用户没提就传 1。")
                .requiresConfirmation(true)
                .parameters(Map.of(
                        "skuId", ToolDefinition.ParameterSpec.builder()
                                .type("integer")
                                .description("要加入购物车的 SKU 编号（规格编号，来自 product_search 的结果）")
                                .required(true).build(),
                        "quantity", ToolDefinition.ParameterSpec.builder()
                                .type("integer")
                                .description("数量，整数。用户没明确说数量时传 1")
                                .required(false).build()
                ))
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String userId = call.requireUserId();
            Long skuId = skuIdOf(call);
            int quantity = quantityOf(call);

            AgentAddCartRequest request = new AgentAddCartRequest();
            request.setSkuId(skuId);
            request.setQuantity(quantity);
            // 写操作：下游说没做成时绝不重发。重发就是加两件——用户只想要一件，
            // 而购物车里会静静多出一件，他自己都未必发现
            AgentCartItem item = Downstream.mutate("购物车", () -> orderClient.addCartItem(userId, request));
            if (item == null) {
                return ToolResult.builder().success(false)
                        .output("加入购物车没有成功，请让用户稍后再试。")
                        .build();
            }
            return ToolResult.builder()
                    .success(true)
                    .output("已加入购物车：" + item.getName()
                            + (item.getSpecText() == null ? "" : "（" + item.getSpecText() + "）")
                            + " × " + item.getQuantity()
                            + "，小计 " + Money.yuan(item.getSubtotal()) + "。"
                            + "提醒用户：加入购物车还没有下单，需要结算并支付才算买到。")
                    .rawData(item)
                    .build();
        } catch (Exception e) {
            return Downstream.failure("加入购物车", e);
        }
    }

    /**
     * SKU 编号必须是一个真实存在的正整数。
     * <p>
     * <b>为什么在这里拦「模型编的编号」。</b>实测反复出现过：模型没有先从检索结果里读出
     * 「规格：SKU<数字>」，而是直接传了一个占位值 {@code 0}。不拦的话这个请求会一路
     * 走到购物车服务，回来的要么是一句与真实成因无关的参数错误，要么（更糟）恰好落到
     * 某个真实 SKU 上——那用户就买到了他没挑过的东西。拦在这里，模型拿到的是一句
     * 「你没有先查商品」，它据此回去做正确的那一步。
     * <p>
     * 判据是「编号必须为正」而不是「编号必须存在于目录」：后者要多打一次下游，
     * 而购物车服务本来就会校验 SKU 是否存在——重复校验换不来更多安全，
     * 只会让每一次加购都多一次往返。
     */
    private Long skuIdOf(ToolCall call) {
        Object raw = call.getArguments().get("skuId");
        if (raw == null) {
            throw new IllegalArgumentException("缺少 skuId；请先用 product_search 查到商品，"
                    + "再从输出里的「规格：SKU<数字>」取编号");
        }
        long skuId;
        try {
            skuId = Long.parseLong(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("skuId 必须是数字，收到的是「" + raw + "」；"
                    + "请先用 product_search 查到商品，再从输出里的「规格：SKU<数字>」取编号");
        }
        if (skuId <= 0) {
            throw new IllegalArgumentException("skuId 必须是一个真实的规格编号，收到的是 " + skuId
                    + "；请先用 product_search 查到商品，再从输出里的「规格：SKU<数字>」取编号");
        }
        return skuId;
    }

    /**
     * 数量兜底到 1。
     * <p>
     * 模型有时会把「来一个」翻译成不传这个参数，也可能传回字符串。
     * 传 0 或负数时**不纠正成 1**——那是模型理解错了，让它照原样失败比替它猜更安全：
     * 悄悄改成 1 会让「我要 0 件」变成真加一件。
     */
    private int quantityOf(ToolCall call) {
        Object raw = call.getArguments().get("quantity");
        if (raw == null) {
            return 1;
        }
        int quantity = Integer.parseInt(String.valueOf(raw).strip());
        if (quantity < 1) {
            throw new IllegalArgumentException("数量必须至少为 1，收到的是 " + quantity);
        }
        return quantity;
    }
}
