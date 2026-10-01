package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/**
 * 结算页试算请求。
 * <p>
 * <b>只有券一个字段 —— 商品明细不来自请求体</b>：它在服务端（购物车 + SKU 快照），
 * 与真正下单时装配的是同一段代码。让前端把 items 传上来「省一次查询」，
 * 就是「预览说能用、提交被拒」那条 bug 的复现路径：两份输入迟早不同。
 */
@Data
public class OrderPreviewRequest {

    /** 想试算的券（用户券 id），选填。传了就把它的抵扣算进应付金额 */
    private Long userCouponId;
}
