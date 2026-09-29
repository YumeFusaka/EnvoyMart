package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 一个规格项及其全部可选值（"容量"：[90粒, 180粒]） */
@Data
@Builder
public class SpecGroup {

    private Long specId;
    private String name;
    private List<Value> values;

    @Data
    @Builder
    public static class Value {
        private Long id;
        private String value;
    }
}
