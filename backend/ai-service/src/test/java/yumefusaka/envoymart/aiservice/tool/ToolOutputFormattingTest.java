package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.contract.ProductSummary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具输出的<b>可读性契约</b>——锁住两类曾经真实发生过、且不会报错的缺陷。
 * <p>
 * <b>一、字段挂错。</b>AI 服务曾自己维护一份 {@code ProductResponse}，字段是
 * {@code price: BigDecimal(元)}，而商品服务实际返回 {@code minPrice: Long(分)}。
 * 两边靠 JSON 字段名对齐，名字对不上时 <b>Jackson 不报错、只给 null</b>，
 * 于是工具输出「商品名 (null 元)」，模型照单全收说给用户。
 * <p>
 * <b>二、单位挂错。</b>金额全链路存「分」，直接拼进给模型看的文本就是 100 倍。
 * 这类错误同样零异常、零日志。
 * <p>
 * 两条的共同点是<b>只在最终回复里可见</b>，所以断言落在工具输出文本上，
 * 而不是落在「字段有没有值」这种中间态上。
 */
class ToolOutputFormattingTest {

    private static final String USER = "u1001";

    private ToolCall call(String tool, Map<String, Object> args) {
        return new ToolCall("t1", tool, args, false, USER);
    }

    // ==================== 商品工具 ====================

    @Test
    void 商品工具把分转成元且不出现_null() {
        ProductClient client = mock(ProductClient.class);
        when(client.recommend(anyString(), anyInt())).thenReturn(Result.success(List.of(
                ProductSummary.builder()
                        .id(7L).name("维生素 D3 软胶囊").subtitle("400IU 每日一粒")
                        .minPrice(4900L).maxPrice(8900L)
                        .totalStock(120).sales(58).ratingAvg(new BigDecimal("4.8"))
                        .build())));

        ToolResult result = new ProductTool(client).execute(call("product_search", Map.of("query", "维生素")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .as("价格必须是「元」而不是原始的分，也不能是 null")
                .contains("49.00 元-89.00 元")
                .contains("有货")
                .as("编号要写成 SPU7——那是同一个商品在图谱与 interaction_check 里的名字。"
                        + "印成裸数字，模型就有两个名字而不知道它们指同一个东西")
                .contains("编号 SPU7")
                .doesNotContain("null");
    }

    @Test
    void 商品工具认得出图谱用的编号写法() {
        ProductClient client = mock(ProductClient.class);
        when(client.getProduct(7L)).thenReturn(Result.success(
                ProductSummary.builder().id(7L).name("鱼油软胶囊").minPrice(9900L).maxPrice(9900L).build()));

        for (String written : List.of("SPU7", "spu7", "SPU 7", "spu007")) {
            ToolResult result = new ProductTool(client).execute(call("product_search", Map.of("query", written)));

            assertThat(result.getOutput())
                    .as("%s 应当被当成编号精确查到，而不是拿去当关键词搜——"
                            + "拿编号当关键词永远搜不到，而那会被模型读成「这个商品不存在」", written)
                    .contains("鱼油软胶囊").contains("SPU7");
        }
        verify(client, never()).recommend(anyString(), anyInt());
    }

    @Test
    void 按编号查不到时的说法不能被读成编号格式不对() {
        ProductClient client = mock(ProductClient.class);
        when(client.getProduct(7L)).thenReturn(Result.error(404, "商品不存在"));

        assertThat(new ProductTool(client).execute(call("product_search", Map.of("query", "SPU7"))).getOutput())
                .contains("目录里没有编号 SPU7")
                .as("实测过：把这种情况说成「没搜到相关内容」，模型会给用户编一段"
                        + "「平台编号可能是别的写法」，而真正的原因是商品已下架")
                .doesNotContain("没有找到与");
    }

    @Test
    void 商品工具价格区间相等时只回一个价格() {
        ProductClient client = mock(ProductClient.class);
        when(client.recommend(anyString(), anyInt())).thenReturn(Result.success(List.of(
                ProductSummary.builder().id(1L).name("蛋白粉")
                        .minPrice(19900L).maxPrice(19900L).totalStock(5).build())));

        String output = new ProductTool(client).execute(call("product_search", Map.of("query", "蛋白粉"))).getOutput();

        assertThat(output).contains("199.00 元").doesNotContain("199.00 元-199.00 元");
    }

    @Test
    void 商品工具空结果给出明确说法而不是空串() {
        ProductClient client = mock(ProductClient.class);
        when(client.recommend(anyString(), anyInt())).thenReturn(Result.success(List.of()));

        ToolResult result = new ProductTool(client).execute(call("product_search", Map.of("query", "不存在的东西")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .as("空串意味着模型没有任何可依据的事实，只能自己编")
                .contains("没有找到");
        assertThat(result.isNoData())
                .as("关键词查空是「换个说法可能就搜到」，执行图要据此重规划一轮。"
                        + "标成普通成功的话，「搜到 0 个」和「搜到 20 个」在图眼里一模一样，"
                        + "replan → act 那个环就永远不会因为「这次没搜到」而转")
                .isTrue();
    }

    @Test
    void 按编号查空算事实不算没查到() {
        ProductClient client = mock(ProductClient.class);
        when(client.getProduct(7L)).thenReturn(Result.error(404, "商品不存在"));

        ToolResult result = new ProductTool(client).execute(call("product_search", Map.of("query", "SPU7")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isNoData())
                .as("编号是精确的：目录里没有就是没有，换个说法也一样。"
                        + "标成「没查到」只会让执行图白花一轮重规划去查同一个不存在的东西")
                .isFalse();
    }

    // ==================== 订单工具 ====================

    @Test
    void 订单工具用中文状态与实付金额() {
        OrderClient client = mock(OrderClient.class);
        when(client.getOrder(anyString(), anyLong())).thenReturn(Result.success(OrderResponse.builder()
                .id(3L).orderNo("YS20260930001")
                .status("PAID").statusText("已支付")
                .payAmount(5900L)
                .items(List.of(OrderItemResponse.builder()
                        .id(11L).spuId(7L).skuId(21L)
                        .spuName("维生素 D3 软胶囊").skuSpecText("规格:400IU×90粒")
                        .unitPrice(5900L).quantity(1).subtotal(5900L)
                        .build()))
                .build()));

        ToolResult result = new OrderTool(client).execute(call("order_query", Map.of("orderId", 3)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput())
                .contains("已支付")
                .as("枚举名 PAID 会被模型原样复述给用户")
                .doesNotContain("PAID")
                .contains("59.00 元")
                .contains("维生素 D3 软胶囊")
                .doesNotContain("null");
    }

    // ==================== 金额格式化本身 ====================

    @Test
    void 金额格式化覆盖空值与个位数分() {
        assertThat(Money.yuan(null)).isEqualTo("暂无报价");
        assertThat(Money.yuan(0L)).isEqualTo("0.00 元");
        assertThat(Money.yuan(5L)).isEqualTo("0.05 元");
        assertThat(Money.yuan(123456L)).isEqualTo("1234.56 元");
        assertThat(Money.yuanRange(null, null)).isEqualTo("暂无报价");
        // 只有一端有值时不拼成 "暂无报价-89.00 元"
        assertThat(Money.yuanRange(null, 8900L)).isEqualTo("89.00 元");
    }
}
