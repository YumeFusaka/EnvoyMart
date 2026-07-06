package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.productservice.entity.ProductEntity;
import yumefusaka.envoymart.productservice.mapper.ProductMapper;
import yumefusaka.envoymart.productservice.model.ProductResponse;
import yumefusaka.envoymart.productservice.model.StockDeductRequest;
import yumefusaka.envoymart.productservice.service.ProductService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;

    public ProductServiceImpl(ProductMapper productMapper,
                              ProductCacheService productCacheService) {
        this.productMapper = productMapper;
        this.productCacheService = productCacheService;
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
        ProductEntity entity = requireEntity(request.getProductId());
        if (entity.getStock() < request.getQuantity()) {
            throw new IllegalArgumentException(entity.getName() + " 库存不足");
        }
        entity.setStock(entity.getStock() - request.getQuantity());
        productMapper.updateById(entity);
        // 库存变更后清除缓存，确保强一致性
        productCacheService.evictProductCache(request.getProductId());
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
                .tags(entity.getTags() == null ? List.of() : Arrays.stream(entity.getTags().split(",")).map(String::trim).toList())
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
