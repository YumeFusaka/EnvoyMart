package yumefusaka.envoymart.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.product.mapper.ProductMapper;
import yumefusaka.envoymart.product.model.entity.ProductEntity;
import yumefusaka.envoymart.product.model.response.ProductResponse;
import yumefusaka.envoymart.product.service.ProductService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;

    public ProductServiceImpl(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public List<ProductResponse> listProducts(String keyword, String category) {
        List<ProductEntity> products = productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                .orderByDesc(ProductEntity::getMonthlySales)
                .orderByAsc(ProductEntity::getPrice));
        return products.stream()
                .filter(product -> matchesKeyword(product, keyword))
                .filter(product -> !StringUtils.hasText(category) || category.equals(product.getCategory()))
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ProductResponse getProduct(Long id) {
        return toResponse(requireEntity(id));
    }

    @Override
    public List<ProductResponse> recommendProducts(String query, int limit) {
        Set<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        return productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                        .orderByDesc(ProductEntity::getMonthlySales)
                        .orderByAsc(ProductEntity::getPrice))
                .stream()
                .filter(product -> keywords.stream().anyMatch(keyword -> matchesKeyword(product, keyword)))
                .limit(limit)
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ProductEntity requireEntity(Long id) {
        ProductEntity entity = productMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        return entity;
    }

    @Override
    public void decreaseStock(Long productId, int quantity) {
        ProductEntity entity = requireEntity(productId);
        if (entity.getStock() < quantity) {
            throw new IllegalArgumentException(entity.getName() + " 库存不足");
        }
        entity.setStock(entity.getStock() - quantity);
        productMapper.updateById(entity);
    }

    private boolean matchesKeyword(ProductEntity product, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        return contains(product.getName(), normalizedKeyword)
                || contains(product.getSubtitle(), normalizedKeyword)
                || contains(product.getBrand(), normalizedKeyword)
                || contains(product.getCategory(), normalizedKeyword)
                || contains(product.getTags(), normalizedKeyword)
                || contains(product.getSemanticKeywords(), normalizedKeyword)
                || contains(product.getDescription(), normalizedKeyword);
    }

    private boolean contains(String source, String keyword) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(keyword);
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
                .tags(entity.getTags() == null ? List.of() : Arrays.stream(entity.getTags().split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toList()))
                .build();
    }

    private Set<String> extractKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return Set.of();
        }
        Set<String> keywords = Arrays.stream(query.replace("，", " ").replace("。", " ").split("\\s+"))
                .flatMap(token -> Arrays.stream(token.split("的")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(token -> token.length() >= 2)
                .filter(token -> !Set.of("推荐", "适合", "学生党", "帮我", "一下", "这个", "订单").contains(token))
                .collect(Collectors.toSet());
        List<String> domainKeywords = List.of("耳机", "百元", "学生", "通勤", "补光灯", "保温杯", "枕头", "降噪");
        for (String domainKeyword : domainKeywords) {
            if (query.contains(domainKeyword)) {
                keywords.add(domainKeyword);
            }
        }
        return keywords;
    }
}
