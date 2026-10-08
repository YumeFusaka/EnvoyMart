package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.tool.PendingAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalProductDisplayTest {
    @Test
    void 加购确认投影不从模型输出推断商品信息或修改执行参数() throws Exception {
        var method = Agent.class.getDeclaredMethod("pendingDetail", PendingAction.class, List.class, List.class);
        method.setAccessible(true);
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("skuId", 52);
        arguments.put("quantity", 1);
        var action = new PendingAction("cart_add", arguments);
        var detail = (Map<?, ?>) method.invoke(null, action, List.of(), List.of());
        assertThat(detail.get("arguments")).isEqualTo(arguments);
        assertThat(action.arguments()).containsOnlyKeys("skuId", "quantity");
    }
}
