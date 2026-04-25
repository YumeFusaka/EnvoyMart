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

    /**
     * 多字段语义搜索：商品名、副标题、描述、分类、品牌
     */
    public List<ProductResponse> search(String keyword, String category, int page, int size) {
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
                .build();
    }
}
