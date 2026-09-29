package yumefusaka.envoymart.productservice.model;

import lombok.Builder;
import lombok.Data;

/** 商品参数项。属性定义挂在类目上，取值挂在商品上 */
@Data
@Builder
public class AttributeView {

    private Long attributeId;
    private String name;
    private String value;
    private String unit;
}
