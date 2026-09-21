package yumefusaka.envoymart.productservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.productservice.cache.ProductBloomFilter;
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
    /** 缓存穿透的第一道防线，详见 getProduct 与 ProductBloomFilter 的注释 */
    private final ProductBloomFilter bloomFilter;
    /** 被布隆过滤器拦下的请求数 —— 穿透防护是否真的在生效，看这个数字 */
    private final Counter bloomRejections;

    public ProductServiceImpl(ProductMapper productMapper,
                              ProductCacheService productCacheService,
                              ObjectProvider<ProductSyncService> searchSync,
                              ProductBloomFilter bloomFilter,
                              MeterRegistry meterRegistry) {
        this.productMapper = productMapper;
        this.productCacheService = productCacheService;
        this.searchSync = searchSync;
        this.bloomFilter = bloomFilter;
        this.bloomRejections = Counter.builder("product.bloom.rejected")
                .description("被布隆过滤器直接拒绝的商品查询数（未触及缓存与数据库）")
                .register(meterRegistry);
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
        // 布隆过滤器是**第一道**，且它是唯一能在"不碰任何下游"的前提下拒绝请求的一层。
        //
        // 它与空值哨兵挡的不是同一类：
        //   空值哨兵缓存的是"这个 id 不存在"，对**重复**查同一个不存在的 id 有效；
        //   随机 id 扫描每次都是新 key、永远不命中，可以一直打到数据库——这正是过滤器挡的。
        //
        // 注意 id 为 null 或过滤器未加载时 mightContain 返回 true（放行），
        // 不让一个"防穿透"的组件把正常流量拦掉。
        if (!bloomFilter.mightContain(id)) {
            // 单独计数：这个数字直接回答"穿透防护到底拦下了多少"。
            // 没有它，就只能靠"响应变快了"这类间接感受去判断，而那种判断不可验证——
            // 实测时我想用 MySQL 的 Com_select 差量来证明"没打库"，
            // 结果背景噪声（健康检查、后台任务）比信号还大，压根测不出来。
            // **能被验证的指标，比"我觉得生效了"值钱。**
            bloomRejections.increment();
            throw new IllegalArgumentException("商品不存在");
        }

        // 读缓存 / 回源 / 回填（含"确认不存在"的空值）都收在缓存服务里，
        // 三种防护（穿透、雪崩、击穿）发生在同一个窗口，散在这里写必然有漏
        ProductResponse product = productCacheService.getOrLoad(id, () -> {
            ProductEntity entity = productMapper.selectById(id);
            return entity == null ? null : toResponse(entity);
        });
        if (product == null) {
            // 缓存已标记不存在，与回源查不到是同一种结果，保持原有的异常语义
            throw new IllegalArgumentException("商品不存在");
        }
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
        log.debug("[Stock] 扣减成功 productId={} quantity={}",
                request.getProductId(), request.getQuantity());
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
