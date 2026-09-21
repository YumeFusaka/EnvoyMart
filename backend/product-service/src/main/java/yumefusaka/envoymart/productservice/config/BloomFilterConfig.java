package yumefusaka.envoymart.productservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yumefusaka.envoymart.productservice.cache.ProductBloomFilter;
import yumefusaka.envoymart.productservice.entity.ProductEntity;
import yumefusaka.envoymart.productservice.mapper.ProductMapper;

import java.util.List;

/**
 * 布隆过滤器的装配与启动加载。
 * <p>
 * 沿用 {@code ProductSyncService} 的做法——启动时全量读一次并灌进内存结构
 * （那边灌 ES 索引，这边灌位数组）。**加载失败不影响启动**：
 * 过滤器未加载时 {@link ProductBloomFilter#mightContain} 一律放行，
 * 穿透防护退化成只有空值哨兵，服务照常可用。
 */
@Slf4j
@Configuration
public class BloomFilterConfig {

    /**
     * 构建并在启动时加载全量商品 id。
     * <p>
     * <b>为什么在启动时而不是懒加载</b>：懒加载意味着第一个请求要等一次全表扫描，
     * 而"第一个请求"往往就是压测或扫描的第一个包。
     * <p>
     * <b>这里没有增量同步，是有意的取舍</b>：本项目商品只来自种子数据、没有运行期新增入口，
     * 启动加载就够了。生产上商品会新增，那时必须补一条路径——商品创建成功后调
     * {@code add(id)}，或按周期调 {@code rebuild()}。<b>漏了同步的后果很严重</b>：
     * 新商品会被误判为"不存在"。这个风险写在 {@link ProductBloomFilter} 的类注释里，不藏着。
     */
    @Bean
    public ProductBloomFilter productBloomFilter(ProductMapper productMapper) {
        ProductBloomFilter filter = new ProductBloomFilter();
        try {
            List<Long> ids = productMapper.selectList(null).stream()
                    .map(ProductEntity::getId)
                    .toList();
            filter.rebuild(ids);
        } catch (Exception e) {
            log.error("[Bloom] 启动加载商品 id 失败，穿透防护退化为仅空值哨兵", e);
        }
        return filter;
    }
}
