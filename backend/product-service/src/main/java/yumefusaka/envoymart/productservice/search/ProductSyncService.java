package yumefusaka.envoymart.productservice.search;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.entity.ProductEntity;
import yumefusaka.envoymart.productservice.mapper.ProductMapper;

import java.util.List;

/**
 * 商品数据 ES 索引同步服务：启动时将 DB 商品同步至 ES
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "product.search.sync-on-startup", havingValue = "true", matchIfMissing = false)
public class ProductSyncService {

    private final ProductMapper productMapper;
    private final ProductSearchRepository searchRepository;

    public ProductSyncService(ProductMapper productMapper,
                              ProductSearchRepository searchRepository) {
        this.productMapper = productMapper;
        this.searchRepository = searchRepository;
    }

    @PostConstruct
    public void syncAllProducts() {
        List<ProductEntity> products = productMapper.selectList(null);
        List<ProductIndex> indices = products.stream()
                .map(this::toIndex)
                .toList();
        searchRepository.saveAll(indices);
        log.info("ES 商品索引同步完成，共 {} 条", indices.size());
    }

    public void syncProduct(ProductEntity entity) {
        searchRepository.save(toIndex(entity));
    }

    public void removeProduct(Long id) {
        searchRepository.deleteById(id);
    }

    private ProductIndex toIndex(ProductEntity entity) {
        return ProductIndex.builder()
                .id(entity.getId())
                .name(entity.getName())
                .subtitle(entity.getSubtitle())
                .category(entity.getCategory())
                .brand(entity.getBrand())
                .tags(entity.getTags())
                .price(entity.getPrice())
                .stock(entity.getStock())
                .monthlySales(entity.getMonthlySales())
                .image(entity.getImage())
                .salesCopy(entity.getSalesCopy())
                .description(entity.getDescription())
                .semanticKeywords(entity.getSemanticKeywords())
                .build();
    }
}
