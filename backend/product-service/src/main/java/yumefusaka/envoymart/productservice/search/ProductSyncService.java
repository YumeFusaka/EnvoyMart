package yumefusaka.envoymart.productservice.search;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.productservice.entity.ProductEntity;
import yumefusaka.envoymart.productservice.mapper.ProductMapper;

import java.util.List;

/**
 * 商品数据 ES 索引同步服务：启动时将 DB 商品同步至 ES。
 * <p>
 * 开关默认 <b>开</b>：本地 H2 每次启动都是空的、ES 却是持久的，不同步的话索引里什么都没有，
 * 而 `/products/search` 会一直报 "Index envoymart_product not found"。
 * 此前这个开关默认关且未在配置与文档中出现——功能静默不可用，不实际跑一次就发现不了。
 * 想关掉（例如商品量大、不希望在启动时全量重灌）显式配 {@code product.search.sync-on-startup=false}。
 * <p>
 * 重复同步是安全的：{@code saveAll} 走 ES 的 upsert 语义，主键相同即覆盖。
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "product.search.sync-on-startup", havingValue = "true", matchIfMissing = true)
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
