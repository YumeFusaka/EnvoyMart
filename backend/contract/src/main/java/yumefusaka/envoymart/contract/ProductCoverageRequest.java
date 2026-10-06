package yumefusaka.envoymart.contract;

import java.util.List;

/**
 * 覆盖率查询的入参 —— 一批「要查的商品」。
 * <p>
 * 带上名字是为了回显：覆盖率读数最终要显示「哪个商品缺资料，点进去补」，
 * 而名字的事实源在 product-service。让 knowledge-service 拿键去反查名字，
 * 等于让它去依赖商品库；由调用方把名字一起送过来，它就只做「查图 + 组装」这一件事。
 *
 * @param spus 要检查的商品。空列表是合法的，返回的是一份全空的读数
 */
public record ProductCoverageRequest(List<SpuRef> spus) {

    /**
     * 一件要检查的商品。
     *
     * @param spuKey 图谱节点键（{@code SPU7}）
     * @param name   商品名，原样回显
     */
    public record SpuRef(String spuKey, String name) {
    }
}