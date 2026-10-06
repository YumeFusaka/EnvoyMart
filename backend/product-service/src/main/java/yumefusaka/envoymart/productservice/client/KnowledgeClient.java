package yumefusaka.envoymart.productservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import yumefusaka.envoymart.common.result.Result;

import java.util.List;

/**
 * 知识服务客户端 —— 商品上下架时联动它的说明书。
 *
 * <p><b>为什么商品服务要主动调知识服务，而不是反过来</b>：上下架是一个同步的管理动作，
 * 运营点完「下架」立刻看到说明书退出检索，是一条可以被观察到的因果；
 * 反过来让知识服务订阅商品事件，就要多维护一个「消息到了没有」的中间态，
 * 而这条链路既没有吞吐压力、也没有跨服务事务要求。
 */
@FeignClient(name = "knowledge-service", url = "${services.knowledge-service-url:http://127.0.0.1:9008}")
public interface KnowledgeClient {

    /**
     * 同步某个商品名下主体文档的上/下架状态。
     *
     * @param on true 上架 / false 下架
     * @return <b>状态真的变化了</b>的文档编号。调用方只重建这几篇
     */
    @PostMapping("/knowledge/internal/products/{spuId}/sync-status")
    Result<List<String>> syncProductStatus(@PathVariable("spuId") Long spuId,
                                           @RequestParam("on") boolean on);
}
