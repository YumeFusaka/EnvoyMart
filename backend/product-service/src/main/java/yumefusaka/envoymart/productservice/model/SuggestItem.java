package yumefusaka.envoymart.productservice.model;

/**
 * 搜索联想的一条候选。
 * <p>
 * {@code type} 不是给模型看的标签，而是**前端决定点它之后去哪**的依据：
 * 商品进详情页，品牌与类目进带筛选条件的商城页。让前端拿文本去猜类型
 * （「这条是不是品牌名」）是猜不准的——商品名里本来就可能含品牌词。
 * <p>
 * {@code id} 与类型对应：PRODUCT 是 SPU id，BRAND 是品牌 id，CATEGORY 是类目 id。
 */
public record SuggestItem(String text, Kind type, Long id) {

    public enum Kind {
        PRODUCT, BRAND, CATEGORY
    }
}
