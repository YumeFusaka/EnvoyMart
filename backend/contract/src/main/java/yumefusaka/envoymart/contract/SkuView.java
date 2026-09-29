package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 在售 SKU 的可售信息。金额单位「分」 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuView {

    private Long id;
    private String skuCode;

    /** 单位「分」 */
    private Long price;
    private Long originalPrice;
    private Integer stock;
    private String image;

    /** 该 SKU 在每个规格项上取的值 id。前端把它与用户选中的组合比对，定位到唯一 SKU */
    private List<Long> specValueIds;

    /** 形如 "容量:90粒;包装:瓶装"。用于展示与订单快照，避免展示时再查一次 */
    private String specText;
}
