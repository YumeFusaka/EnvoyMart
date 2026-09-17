package yumefusaka.envoymart.productservice.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.model.ProductResponse;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ES 商品搜索服务：支持关键词匹配与多字段组合查询
 */
@Slf4j
@Service
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    public ProductSearchService(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    /** ES 的 max_result_window 默认就是 10000，from+size 超过它查询直接失败。 */
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 多字段语义搜索：商品名、副标题、描述、分类、品牌
     * <p>
     * 分页参数在这里校验，而不是直接交给 {@code PageRequest.of} 和 ES。原先两个参数都是裸的：
     * {@code page=-1} / {@code size=0} 会被 Spring Data 拒绝并抛出它自己的文案
     * （"Page index must not be less than zero"），{@code size=10001} 又会撞上 ES 的
     * max_result_window 报 "all shards failed"。两种情况都以 code=500 的形式回给调用方，
     * 而且这个接口<b>匿名可达</b>——分不清是参数写错了还是服务端挂了，还顺带把
     * Spring Data 与 ES 的内部消息读了出去。
     */
    public List<ProductResponse> search(String keyword, String category, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("页码不能为负");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        if ((long) page * size >= MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("页码超出可检索范围，请缩小范围或改用分类筛选");
        }

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // 关键词多字段匹配
        if (keyword != null && !keyword.isBlank()) {
            Query keywordQuery = new MultiMatchQuery.Builder()
                    .fields("name^3", "subtitle^2", "description", "category", "brand", "tags", "semanticKeywords")
                    .query(keyword)
                    .build()._toQuery();
            boolBuilder.must(keywordQuery);
        }

        // 分类过滤
        if (category != null && !category.isBlank()) {
            Query categoryQuery = new MatchQuery.Builder()
                    .field("category")
                    .query(category)
                    .build()._toQuery();
            boolBuilder.filter(categoryQuery);
        }

        NativeQuery nativeQuery = new NativeQueryBuilder()
                .withQuery(boolBuilder.build()._toQuery())
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<ProductIndex> hits = elasticsearchOperations.search(nativeQuery, ProductIndex.class);
        List<ProductResponse> results = hits.stream()
                .map(SearchHit::getContent)
                .map(this::toResponse)
                .collect(Collectors.toList());

        log.info("ES 搜索: keyword={}, category={}, hits={}", keyword, category, hits.getTotalHits());
        return results;
    }

    /**
     * 根据 ID 列表批量查询（用于推荐结果回查）
     */
    public List<ProductResponse> findByIds(List<Long> ids) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        ids.forEach(id -> bool.should(new MatchQuery.Builder().field("id").query(id).build()._toQuery()));

        SearchHits<ProductIndex> hits = elasticsearchOperations.search(
                new NativeQueryBuilder()
                        .withQuery(bool.build()._toQuery())
                        .withPageable(PageRequest.of(0, ids.size()))
                        .build(),
                ProductIndex.class);

        return hits.stream()
                .map(SearchHit::getContent)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private ProductResponse toResponse(ProductIndex index) {
        return ProductResponse.builder()
                .id(index.getId())
                .name(index.getName())
                .subtitle(index.getSubtitle())
                .category(index.getCategory())
                .brand(index.getBrand())
                .price(index.getPrice())
                .stock(index.getStock())
                .monthlySales(index.getMonthlySales())
                .image(index.getImage())
                .salesCopy(index.getSalesCopy())
                .description(index.getDescription())
                .tags(ProductResponse.splitTags(index.getTags()))
                .build();
    }
}
