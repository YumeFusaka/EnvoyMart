package yumefusaka.envoymart.productservice.model;

import lombok.Data;

/** SKU 的规格明细投影：三个 id 加上给人看的规格名与规格值 */
@Data
public class SkuSpecView {

    private Long skuId;
    private Long specId;
    private String specName;
    private Long specValueId;
    private String specValue;
    private Integer specSort;
}
