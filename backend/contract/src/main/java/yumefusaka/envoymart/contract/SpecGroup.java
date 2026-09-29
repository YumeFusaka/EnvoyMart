package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 一个规格项及其全部可选值（"容量"：[90粒, 180粒]） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecGroup {

    private Long specId;
    private String name;
    private List<Value> values;

    /**
     * 规格值。它是 {@code List<Value>} 的元素，<b>和 SpecGroup 一样要过 JSON</b>，
     * 所以同样需要那一对构造器——嵌套类最容易漏，因为它不在包目录下，
     * 按包名扫描的检查会漏掉它（本模块的往返测试是靠递归构造才发现的）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Value {
        private Long id;
        private String value;
    }
}
