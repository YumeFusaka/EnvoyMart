package yumefusaka.envoymart.aiservice.tool;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.aiservice.client.AuthClient;
import yumefusaka.envoymart.aiservice.model.AgentAddress;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地址查询工具 —— 锁住「读得到、但不替用户做主」这条边界。
 * <p>
 * 这三个用例对应三种真实处境，缺任何一条都会让「帮我把购物车下单」退化成
 * 「请你自己把地址打一遍」：地址簿为空时要说清没有、而不是编；有默认时要把
 * 那条念出来；<b>没有默认时不能顺手拿第一条</b>——地址簿的排序是插入顺序，
 * 与用户偏好无关，赌错了一次就是退不掉的错单。
 */
class AddressToolTest {

    private static final String USER = "u1001";

    private ToolCall call() {
        return new ToolCall("t1", "address_list", Map.of(), false, USER);
    }

    private static AgentAddress addr(long id, String name, int isDefault) {
        AgentAddress a = new AgentAddress();
        a.setId(id);
        a.setReceiverName(name);
        a.setReceiverPhone("13800000000");
        a.setProvince("浙江省");
        a.setCity("杭州市");
        a.setDistrict("西湖区");
        a.setDetail("文一西路 100 号 1 幢 101");
        a.setIsDefault(isDefault);
        return a;
    }

    @Test
    void 地址簿为空时明确说没有而不是编一个出来() {
        AuthClient client = mock(AuthClient.class);
        when(client.listAddresses(anyString())).thenReturn(Result.success(List.of()));

        ToolResult r = new AddressTool(client).execute(call());

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).contains("空").contains("收货人");
    }

    @Test
    void 有默认地址时把这条地址念出来供用户确认() {
        AuthClient client = mock(AuthClient.class);
        when(client.listAddresses(anyString()))
                .thenReturn(Result.success(List.of(addr(1L, "张三", 0), addr(2L, "李四", 1))));

        ToolResult r = new AddressTool(client).execute(call());

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).contains("李四").contains("杭州市").contains("确认");
    }

    @Test
    void 没有默认地址时不擅自取第一条而是让用户指定() {
        AuthClient client = mock(AuthClient.class);
        when(client.listAddresses(anyString()))
                .thenReturn(Result.success(List.of(addr(1L, "张三", 0), addr(2L, "李四", 0))));

        ToolResult r = new AddressTool(client).execute(call());

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).contains("没有标记为默认").contains("不要替用户挑");
    }
}
