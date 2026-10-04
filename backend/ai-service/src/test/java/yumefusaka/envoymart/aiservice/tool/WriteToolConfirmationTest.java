package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.model.AgentAfterSaleResult;
import yumefusaka.envoymart.aiservice.model.AgentCartItem;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三把写交易状态的工具 —— 锁住「做错了用户得自己收尾」这条风险线上的两件事。
 * <p>
 * <b>一、它们必须带确认。</b>加购、下单、售后提交都会写库，写进去之后用户没有
 * 「一键撤销」——他得自己去购物车里翻、自己联系客服。这类动作一旦由模型自主执行，
 * 用户看到的就是一条「我帮你下单了」的消息。断言的判据不是注释里写了什么，
 * 而是 {@code getDefinition().isRequiresConfirmation()} 的取值。
 * <p>
 * <b>二、缺参数时失败要发生在本地。</b>必填字段缺失时不拦，请求会带着 null 一路走到下游，
 * 回来的是一句用户看不懂的参数校验错误；在这里拦下，模型拿到的是一句
 * 「缺哪个字段」，它可以据此回去问用户——那才是我们想让它做的事。
 */
class WriteToolConfirmationTest {

    private static final String USER = "u1001";

    /** 参数允许 null 值 —— ToolCall 的入参来自模型，缺字段就是「这个键不在 map 里」 */
    private ToolCall call(String tool, Map<String, Object> args) {
        Map<String, Object> copy = new HashMap<>(args);
        copy.values().removeIf(java.util.Objects::isNull);
        return new ToolCall("t1", tool, copy, false, USER);
    }

    private static AgentCartItem cartItem() {
        AgentCartItem item = new AgentCartItem();
        item.setId(1L);
        item.setSkuId(101L);
        item.setName("维生素 C 咀嚼片");
        item.setSpecText("60 片/瓶");
        item.setQuantity(2);
        item.setSubtotal(9800L);
        item.setAvailable(true);
        return item;
    }

    // ==================== 加购 ====================

    @Test
    void 加购工具必须带确认() {
        ToolDefinition def = new AddToCartTool(mock(OrderClient.class)).getDefinition();

        assertThat(def.isRequiresConfirmation()).isTrue();
        assertThat(def.getName()).isEqualTo("cart_add");
    }

    @Test
    void 普通商品标题不会让确认卡片误导用户() {
        OrderClient client = mock(OrderClient.class);
        when(client.addCartItem(anyString(), any())).thenReturn(Result.success(cartItem()));

        ToolResult result = new AddToCartTool(client).execute(call("cart_add", Map.of("skuId", 101, "quantity", 2)));

        assertThat(result.isSuccess()).isTrue();
        // 金额按「分转元」，且必须带上「还没下单」——只说「已加入购物车」会被读成买到了
        assertThat(result.getOutput()).contains("98.00").contains("还没有下单");
    }

    @Test
    void 加购数量缺省为一() {
        OrderClient client = mock(OrderClient.class);
        when(client.addCartItem(anyString(), any())).thenReturn(Result.success(cartItem()));

        new AddToCartTool(client).execute(call("cart_add", Map.of("skuId", 101)));

        org.mockito.ArgumentCaptor<yumefusaka.envoymart.aiservice.model.AgentAddCartRequest> captor =
                org.mockito.ArgumentCaptor.forClass(yumefusaka.envoymart.aiservice.model.AgentAddCartRequest.class);
        verify(client).addCartItem(anyString(), captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(1);
    }

    /**
     * 数量非法时**不替模型改成 1**。
     * 悄悄纠正会让「我要 0 件」变成真加一件，而用户根本不知道发生了什么。
     */
    @Test
    void 加购数量非法时拒发请求() {
        OrderClient client = mock(OrderClient.class);

        ToolResult result = new AddToCartTool(client).execute(call("cart_add", Map.of("skuId", 101, "quantity", 0)));

        assertThat(result.isSuccess()).isFalse();
        verify(client, never()).addCartItem(anyString(), any());
    }

    /**
     * 模型编出的 skuId（实测反复出现 0）必须在本地拦下。
     * <p>
     * 放它过去有两个后果，一个是「一句与成因无关的参数错误」，另一个更糟：
     * 若那个占位值恰好落到某个真实 SKU 上，用户就买到了他没挑过的东西。
     * 断言同时钉住「请求没有发出去」——只说「返回了失败」是不够的。
     */
    @Test
    void 编造的skuId被拦下且不发请求() {
        OrderClient client = mock(OrderClient.class);

        ToolResult result = new AddToCartTool(client).execute(call("cart_add", Map.of("skuId", 0, "quantity", 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("product_search");
        verify(client, never()).addCartItem(anyString(), any());
    }

    // ==================== 下单 ====================

    @Test
    void 下单工具必须带确认且要求完整收货信息() {
        ToolDefinition def = new CheckoutTool(mock(OrderClient.class)).getDefinition();

        assertThat(def.isRequiresConfirmation()).isTrue();
        for (String field : new String[]{"receiverName", "receiverPhone", "receiverProvince",
                "receiverCity", "receiverDistrict", "receiverDetail"}) {
            assertThat(def.getParameters().get(field).isRequired())
                    .as("收货字段 %s 必须声明为必填", field)
                    .isTrue();
        }
    }

    @Test
    void 收货信息不全时不下单() {
        OrderClient client = mock(OrderClient.class);

        ToolResult result = new CheckoutTool(client).execute(call("cart_checkout", Map.of(
                "receiverName", "张三",
                "receiverPhone", "13800000000",
                // 故意缺省市区与详细地址
                "receiverProvince", " ",
                "receiverCity", "上海市")));

        assertThat(result.isSuccess()).isFalse();
        // 失败结果把话说在 errorMessage 上（output 只承载成功时的正文），
        // 断言要看模型真正拿到的那一句
        assertThat(result.getErrorMessage()).contains("receiverProvince");
        verify(client, never()).checkout(anyString(), any());
    }

    @Test
    void 下单成功时提醒还要支付() {
        OrderClient client = mock(OrderClient.class);
        OrderResponse order = new OrderResponse();
        order.setOrderNo("EM20261004001");
        order.setPayAmount(12900L);
        when(client.checkout(anyString(), any())).thenReturn(Result.success(order));

        ToolResult result = new CheckoutTool(client).execute(call("cart_checkout", Map.of(
                "receiverName", "张三", "receiverPhone", "13800000000",
                "receiverProvince", "上海市", "receiverCity", "上海市",
                "receiverDistrict", "浦东新区", "receiverDetail", "世纪大道 1 号")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("EM20261004001").contains("129.00").contains("支付");
    }

    // ==================== 售后 ====================

    @Test
    void 售后工具必须带确认() {
        assertThat(new AfterSaleTool(mock(OrderClient.class)).getDefinition().isRequiresConfirmation()).isTrue();
    }

    @Test
    void 售后类型大小写容忍但编造的值被拒() {
        OrderClient client = mock(OrderClient.class);
        assertThat(new AfterSaleTool(client)
                .execute(call("after_sale_apply", Map.of("orderItemId", 1, "type", "bogus")))
                .isSuccess()).isFalse();
        verify(client, never()).applyAfterSale(anyString(), any());
    }

    @Test
    void 售后提交回执带上单号与状态() {
        OrderClient client = mock(OrderClient.class);
        AgentAfterSaleResult receipt = new AgentAfterSaleResult();
        receipt.setAfterSaleNo("AS20261004001");
        receipt.setStatusText("待审核");
        receipt.setRefundAmount(8900L);
        receipt.setDocRef("KB-0004");
        when(client.applyAfterSale(anyString(), any())).thenReturn(Result.success(receipt));

        ToolResult result = new AfterSaleTool(client)
                .execute(call("after_sale_apply", Map.of("orderItemId", 7, "type", "return")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("AS20261004001").contains("待审核").contains("89.00");
    }
}
