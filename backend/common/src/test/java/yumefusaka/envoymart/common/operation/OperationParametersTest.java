package yumefusaka.envoymart.common.operation;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class OperationParametersTest {
    @Test
    void 顺序和整数表示不改变哈希但用户工具参数会改变() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("skuId", 7L);
        first.put("quantity", 2);
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("quantity", 2L);
        reverse.put("skuId", 7);
        String hash = OperationParameters.hash("用户甲", "cart_add", first);
        assertThat(hash).hasSize(64).isEqualTo(OperationParameters.hash("用户甲", "cart_add", reverse));
        assertThat(hash).isNotEqualTo(OperationParameters.hash("用户乙", "cart_add", first));
        assertThat(hash).isNotEqualTo(OperationParameters.hash("用户甲", "cart_update", first));
        assertThat(hash).isNotEqualTo(OperationParameters.hash("用户甲", "cart_add", Map.of("skuId", 7, "quantity", 3)));
    }
}
