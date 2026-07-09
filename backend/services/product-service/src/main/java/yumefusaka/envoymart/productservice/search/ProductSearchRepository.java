package yumefusaka.envoymart.productservice.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 商品 ES 搜索仓库
 */
@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductIndex, Long> {

    List<ProductIndex> findByNameContainingOrSubtitleContainingOrCategoryContainingOrBrandContainingOrTagsContaining(
            String name, String subtitle, String category, String brand, String tags);
}
