package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderQuery;
import yumefusaka.envoymart.orderservice.model.admin.AdminOrderSummary;
import yumefusaka.envoymart.orderservice.model.admin.AdminShipRequest;

/**
 * 订单域的管理侧读与写。
 * <p>
 * 与 {@link OrderDomainService} 的分工是<b>可见性</b>，不是权限：那个接口只看得见
 * 「你自己的单」（每个方法都以 {@code userId} 收口），这个接口看得见全库。
 * 权限由 {@code @RequireAdmin} 在控制器上判定，这一层不重复做——把授权写进业务方法，
 * 会让「这个方法到底该谁能调」变成散在代码里的隐含前提。
 */
public interface OrderAdminService {

    /** 管理列表：按状态、用户、订单号/收货人、下单时间范围筛选 */
    PageResult<AdminOrderSummary> list(AdminOrderQuery query);

    /** 详情：订单主体 + 订单行 + 状态流水 + 履约轨迹 */
    AdminOrderDetail detail(Long orderId);

    /**
     * 发货。承运商与运单号在这里落成履约单与首条轨迹。
     *
     * @param operatorId 发货人，写进状态流水的 operator_id —— 没有它，
     *                   流水只能证明「订单变已发货了」，证明不了「谁发的」
     * @return 更新后的那一行。<b>不是 OrderResponse</b>：那个类型带完整收货地址，
     *         而管理列表刻意只给到姓名与电话，不能从写接口漏出去
     */
    AdminOrderSummary ship(Long orderId, AdminShipRequest request, String operatorId);

    /**
     * 写商家备注。<b>传空串就是清除备注</b>，不是「这次不改」——
     * 一个改不掉的备注比没有备注更糟，运营会一直看到一句已经作废的话。
     *
     * @return 更新后的那一行，带归一化过的备注（空白已折成 null）
     */
    AdminOrderSummary remark(Long orderId, String remark);
}
