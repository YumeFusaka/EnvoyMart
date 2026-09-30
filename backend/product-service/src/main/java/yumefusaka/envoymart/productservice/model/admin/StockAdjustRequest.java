package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 单独调整某个 SKU 的库存。商品表单里的库存字段走同一条逻辑，只是入口不同 */
@Data
public class StockAdjustRequest {

    /** 目标库存，不是增量 */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    /** 写进库存流水的备注（"供应商到货" "盘点修正"）。流水里没有理由就只是数字 */
    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
