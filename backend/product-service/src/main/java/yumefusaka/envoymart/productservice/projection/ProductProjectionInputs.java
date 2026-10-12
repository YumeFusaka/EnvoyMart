package yumefusaka.envoymart.productservice.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 锁住投影的所有数据库输入；缺行仍须由未完成任务来源二次核验。 */
@Component
public class ProductProjectionInputs {
    private final JdbcTemplate jdbc;
    public ProductProjectionInputs(javax.sql.DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
    }

    public void lock(long spuId) {
        List<Map<String, Object>> spus = jdbc.queryForList("select id,category_id,brand_id from product_spu where id=? for update", spuId);
        List<Long> skuIds = jdbc.queryForList("select id from product_sku where spu_id=? order by id for update", Long.class, spuId);
        List<Long> specs = jdbc.queryForList("select id from product_spec where spu_id=? order by id for update", Long.class, spuId);
        lockIds("product_spec_value", "spec_id", specs);
        lockIds("product_sku_spec", "sku_id", skuIds);
        List<Long> attributes = jdbc.queryForList("select id,attribute_id from spu_attribute_value where spu_id=? order by id for update", spuId)
                .stream().map(value -> ((Number) value.get("attribute_id")).longValue()).toList();
        lockIds("product_attribute", "id", attributes);
        for (Map<String, Object> spu : spus) {
            lockIds("category", "id", number(spu.get("category_id")));
            lockIds("brand", "id", number(spu.get("brand_id")));
        }
    }

    private static List<Long> number(Object value) { return value instanceof Number n ? List.of(n.longValue()) : List.of(); }

    private void lockIds(String table, String key, Collection<Long> values) {
        List<Long> ids = values.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) return;
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        // 表/列均来自本类固定调用，值始终参数化。
        jdbc.queryForList("select id from " + table + " where " + key + " in (" + marks + ") order by id for update", ids.toArray());
    }
}
