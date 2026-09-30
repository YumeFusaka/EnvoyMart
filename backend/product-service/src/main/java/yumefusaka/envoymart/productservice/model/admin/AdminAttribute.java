package yumefusaka.envoymart.productservice.model.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 类目的参数模板项。
 * <p>
 * 模板挂在**类目**上而不是商品上：同一类商品的参数名是固定的（「净含量」「保质期」），
 * 商品只填值。所以 {@link #id} 是「参数定义」的 id，商品侧提交的
 * {@code AttributeRequest.attributeId} 指的就是它 —— 前端拿到这份列表，
 * 才知道该给运营摆哪几个输入框。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAttribute {

    private Long id;

    private Long categoryId;

    private String name;

    /** 输入控件类型，如 text / number / select。前端据此选控件 */
    private String inputType;

    /** 单位，如「克」「天」。可空 */
    private String unit;

    private Integer sort;
}
