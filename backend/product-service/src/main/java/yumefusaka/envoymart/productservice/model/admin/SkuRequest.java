package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理端提交的一行 SKU。新建时 {@code id} 为空，编辑时带回原 id */
@Data
public class SkuRequest {

    /** 编辑既有 SKU 时必须带上：订单引用的是 SKU id，丢了 id 就等于删旧建新 */
    private Long id;

    /** 留空则自动生成 */
    @Size(max = 64, message = "SKU 编码最长 64 个字符")
    private String skuCode;

    /** 售价，单位「分」。不用小数：金额的浮点误差会破坏「子项之和 = 总额」这个恒等式 */
    @NotNull(message = "售价不能为空")
    @Min(value = 0, message = "售价不能为负")
    private Long price;

    @Min(value = 0, message = "划线价不能为负")
    private Long originalPrice;

    /**
     * 目标库存（不是增量）。
     * <p>
     * 提交目标值而不是增量，是因为管理台的表单里显示的就是库存本身——让前端算差值，
     * 会在并发编辑时把「我看到的是 10」变成一次错误的加减。
     */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    @Size(max = 512)
    private String image;

    /** 1 启用 / 0 停用。停用后前台详情里看不到这个规格组合，但它仍被历史订单引用 */
    private Integer status = 1;

    /**
     * 规格名 → 规格值名，如 {@code {"净含量": "90粒"}}。
     * <p>
     * 用「名字」而不是 id 关联：管理员在同一个表单里既定义规格、又勾选 SKU 的规格值，
     * 此刻规格值还没有 id（要等保存时才落库）。用名字对接，表单与新数据是同一套表达。
     * <p>
     * 空表表示这个商品没有规格维度——单规格商品就是这种形态。
     */
    private Map<String, String> specValues = new LinkedHashMap<>();
}
