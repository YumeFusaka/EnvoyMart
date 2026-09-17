package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.productservice.entity.ProductEntity;
import yumefusaka.envoymart.productservice.mapper.ProductMapper;
import yumefusaka.envoymart.productservice.model.ProductResponse;
import yumefusaka.envoymart.productservice.model.StockDeductRequest;
import yumefusaka.envoymart.productservice.search.ProductSyncService;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;
    /** 可能不注册（{@code product.search.sync-on-startup=false} 时），所以用 ObjectProvider 而不是直接注入 */
    private final ObjectProvider<ProductSyncService> searchSync;

    public ProductServiceImpl(ProductMapper productMapper,
                              ProductCacheService productCacheService,
                              ObjectProvider<ProductSyncService> searchSync) {
        this.productMapper = productMapper;
        this.productCacheService = productCacheService;
        this.searchSync = searchSync;
    }

    @Override
    public List<ProductResponse> listProducts(String keyword, String category) {
        return productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                        .orderByDesc(ProductEntity::getMonthlySales)
                        .orderByAsc(ProductEntity::getPrice))
                .stream()
                .filter(product -> matchesKeyword(product, keyword))
                .filter(product -> !StringUtils.hasText(category) || category.equals(product.getCategory()))
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ProductResponse getProduct(Long id) {
        ProductResponse cached = productCacheService.getCachedProduct(id);
        if (cached != null) {
            return cached;
        }
        ProductResponse product = toResponse(requireEntity(id));
        productCacheService.cacheProduct(product);
        return product;
    }

    @Override
    public List<ProductResponse> recommendProducts(String query, int limit) {
        Set<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        return productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                        .orderByDesc(ProductEntity::getMonthlySales))
                .stream()
                .filter(product -> keywords.stream().anyMatch(keyword -> matchesKeyword(product, keyword)))
                .limit(limit)
                .map(this::toResponse)
                .toList();
    }

    @Override
    public void deductStock(StockDeductRequest request) {
        // 判断与扣减在数据库端一条语句里完成，避免读-改-写竞态导致的少扣
        int affected = productMapper.deductStock(request.getProductId(), request.getQuantity());
        if (affected == 0) {
            // 影响行数为 0 只有两种可能：商品不存在，或库存不足。分开报错便于排查
            ProductEntity entity = productMapper.selectById(request.getProductId());
            throw new IllegalArgumentException(entity == null
                    ? "商品不存在：" + request.getProductId()
                    : entity.getName() + " 库存不足");
        }
        productCacheService.evictProductCache(request.getProductId());
        syncSearchIndex(request.getProductId());
    }

    @Override
    public void restoreStock(StockDeductRequest request) {
        int affected = productMapper.restoreStock(request.getProductId(), request.getQuantity());
        if (affected == 0) {
            throw new IllegalArgumentException("商品不存在：" + request.getProductId());
        }
        productCacheService.evictProductCache(request.getProductId());
        syncSearchIndex(request.getProductId());
    }

    /**
     * 把变更后的库存写回搜索索引。
     * <p>
     * 索引原先只在 {@code @PostConstruct} 里全量灌一次，运行期的库存变更永远不写回：
     * 实测扣减 5 件后 {@code GET /products/1} 返回 115，而 {@code /products/search} 仍返回 120，
     * 同一件商品两个接口给出不同库存，要等到下次重启全量同步才被纠正。索引里的 stock
     * 会被前端拿去做库存提示和加购联动，长期失真比"干脆没有这个字段"更糟。
     * <p>
     * 同步失败只记日志：搜索索引是派生数据，ES 抖动不该让扣库存这种核心操作跟着失败。
     * 漏掉的那次由下次启动的全量同步兜底。
     */
    private void syncSearchIndex(Long productId) {
        ProductSyncService sync = searchSync.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            ProductEntity entity = productMapper.selectById(productId);
            if (entity != null) {
                sync.syncOne(entity);
            }
        } catch (Exception e) {
            log.warn("同步商品 {} 到搜索索引失败: {}", productId, e.getMessage());
        }
    }

    private ProductEntity requireEntity(Long id) {
        ProductEntity entity = productMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return entity;
    }

    private boolean matchesKeyword(ProductEntity product, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return contains(product.getName(), normalized)
                || contains(product.getSubtitle(), normalized)
                || contains(product.getCategory(), normalized)
                || contains(product.getBrand(), normalized)
                || contains(product.getTags(), normalized)
                || contains(product.getSemanticKeywords(), normalized)
                || contains(product.getDescription(), normalized);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private ProductResponse toResponse(ProductEntity entity) {
        return ProductResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .subtitle(entity.getSubtitle())
                .category(entity.getCategory())
                .brand(entity.getBrand())
                .price(entity.getPrice())
                .stock(entity.getStock())
                .monthlySales(entity.getMonthlySales())
                .image(entity.getImage())
                .salesCopy(entity.getSalesCopy())
                .description(entity.getDescription())
                .tags(ProductResponse.splitTags(entity.getTags()))
                .build();
    }

    private Set<String> extractKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return Set.of();
        }
        Set<String> keywords = Arrays.stream(query.replace("，", " ").replace("。", " ").split("\\s+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(value -> value.length() >= 2)
                .collect(Collectors.toSet());
        for (String keyword : List.of("耳机", "百元", "学生", "通勤", "补光灯", "保温杯", "枕头", "降噪")) {
            if (query.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }
}
