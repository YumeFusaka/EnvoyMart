package yumefusaka.envoymart.reviewservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

/**
 * 商品批量查询 —— 给「我的评价」补上商品卡片数据。
 * <p>
 * <b>为什么是批量而不是逐个查</b>：一页 20 条评价可能横跨 20 个商品，逐个查就是 20 次
 * 跨服务往返，而这 20 个商品里通常还有重复购买的。一次请求把整页的 spuId 带过去，
 * 往返数与条数无关。
 * <p>
 * <b>失败要能降级</b>：调用方把异常吞掉、商品字段留 null —— product-service 抖动时，
 * 「我的评价」应该还能看到自己写了什么，只是看不到商品缩略图，而不是整页打不开。
 */
@FeignClient(name = "product-service", url = "${services.product-service-url:http://127.0.0.1:9002}")
public interface ProductClient {

    /**
     * ids 用逗号串而不是 {@code List<Long>}：Feign 对集合参数的序列化格式取决于
     * 用的是哪个 contract，换一个编码器就可能变成 {@code ids[0]=1} 这种 Spring
     * 绑不上的形状。拼成串在两端都只有一种解释。
     */
    @GetMapping("/products/summaries")
    Result<List<ProductSummary>> summaries(@RequestParam("ids") String ids);
}
