package yumefusaka.envoymart.productservice.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 商品 ES 仓库。
 * <p>
 * 只承担写入（{@code saveAll} / {@code save} / {@code deleteById}）。查询不走这里，
 * 而是 {@link ProductSearchService} 里的 {@code NativeQueryBuilder} ——
 * 组合条件（多字段匹配 + 类目子树 + 价格区间 + 排序）用派生查询的方法名表达不出来，
 * 硬写会变成一个几十个单词、还没法加排序的方法名。
 * <p>
 * <b>原先这里挂着一个从没被调用过的派生查询方法</b>
 * （{@code findByNameContainingOr...OrBrandContaining}），却在字段改名后
 * 让整个应用起不来：Spring Data 在<b>启动时</b>解析方法名，找不到对应属性就直接失败。
 * <b>没人用的方法在启动期依然有杀伤力。</b>
 */
@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductIndex, Long> {
}
