package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.orderservice.model.CheckoutRequest;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.orderservice.model.DeliveryStatus;
import yumefusaka.envoymart.orderservice.model.OrderListQuery;
import yumefusaka.envoymart.orderservice.model.OrderPreviewRequest;
import yumefusaka.envoymart.orderservice.model.OrderPreviewResponse;
import yumefusaka.envoymart.orderservice.model.OrderTabCount;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单域。
 * <p>
 * 购物车不在这里 —— 它已拆到 {@link CartService}。两者是两个聚合：
 * 购物车可以长期存在、随时改动；订单一旦创建就进入只进不退的状态流转。
 */
public interface OrderDomainService {

    OrderResponse checkout(String userId, CheckoutRequest request);

    /**
     * 结算页试算：金额、券的可用性与抵扣。
     * <p>
     * <b>商品明细取自服务端</b>（购物车已勾选条目 + SKU 快照），与 {@link #checkout}
     * 装配的是同一份输入。这不是为了省一次查询，而是为了让「预览说能用」与
     * 「提交被拒」不再可能同时发生 —— 那份分叉的根因就是两边各算各的。
     * <p>
     * 不落任何库、不核销券：它是一次读操作。
     */
    OrderPreviewResponse preview(String userId, OrderPreviewRequest request);

    /** 我的订单，按状态页签分页 */
    PageResult<OrderResponse> listOrders(String userId, OrderListQuery query);

    /**
     * 我的订单各页签的数量，给页签角标用。
     * <p>
     * 单独一个接口而不是塞进列表返回体：翻页时角标不该跟着变，
     * 而它必须与列表<b>同一时刻</b>的口径 —— 分六次查会取到六个快照。
     */
    List<OrderTabCount> orderSummary(String userId);

    OrderResponse getOrder(String userId, Long orderId);

    LogisticsResponse getLogistics(String userId, Long orderId);

    /**
     * 按 id 取订单，<b>不校验归属</b>。
     * <p>
     * <b>只给管理侧用。</b>用户侧入口必须走 {@link #getOrder(String, Long)} ——
     * 那条路径上的 {@code userId} 条件就是归属校验本身，绕过它等于让任何人凭订单号
     * 读到别人的收货地址与电话。
     * <p>
     * 单独留这样一条，而不是让管理侧把 {@code getOrder} 的 userId 传成订单自己的：
     * 后者读起来像校验过了，实际上「先查出来才知道该传什么」——校验从条件变成了装饰。
     */
    OrderResponse orderById(Long orderId);

    /** 同上，物流轨迹的管理侧读取：归属校验在用户侧入口，这里只做映射 */
    LogisticsResponse logisticsOf(Long orderId);

    /** 取消订单（高危操作，仅供已确认的调用方使用） */
    OrderResponse cancelOrder(String userId, Long orderId);

    /**
     * 发货。**管理侧动作，没有调用方用户身份。**
     * <p>
     * 它同时创建履约单与首条物流轨迹 —— 轨迹是真实落库的数据，
     * 而不是按订单时间推算出来的。
     *
     * @param operatorId 发货人，写进状态流水。允许为 null（服务间调用），
     *                   但管理台那条路一定会传 —— 流水里没有操作人，
     *                   就只能证明「订单变已发货了」，证明不了「谁发的」
     */
    OrderResponse shipOrder(Long orderId, String carrierCode, String carrierName,
                            String trackingNo, String operatorId);

    /**
     * 确认收货。用户动作 —— 只有订单归属者能确认。
     * <p>
     * 这一步是售后与评价的**前置条件**：没有它，订单永远停在「已发货」，
     * 政策引擎要求的「已收货」不可达，整条售后链路走不通。
     */
    OrderResponse confirmReceipt(String userId, Long orderId);

    /**
     * 支付完成回调 —— 把订单推进到已支付。
     * <p>
     * 入参只有 orderId：事件来自 MQ，没有调用方身份，也不该有（它表达的是"渠道收到了钱"，
     * 不是"某个用户在操作"）。幂等由状态条件更新保证，重复投递不会出问题。
     */
    void markPaid(Long orderId);

    /**
     * 补录一条物流节点（管理侧动作）。
     * <p>
     * 入参用领域类型（{@link DeliveryStatus} 与几个字符串），不是管理端的请求 DTO ——
     * 后者属于接口层，让它下沉到这里等于把"管理台长什么样"写进领域。
     * 翻译那一层放在 {@code OrderAdminService}，与 {@link #shipOrder} 同一分工。
     *
     * @param description 留空即按状态取默认说明
     * @param happenAt    节点实际发生的时间。补录多半是事后补，按录入时间落库会让
     *                    轨迹的时间顺序与事实不符，而轨迹的全部意义就是那个顺序
     * @param operatorId  补录人。轨迹本身没有操作人列，操作人写进订单状态流水
     *                    （from 为空、to 为当前状态，表示"这是一次记录而非流转"）
     */
    void addDeliveryTrace(Long orderId, DeliveryStatus status, String description,
                          String location, LocalDateTime happenAt, String operatorId);
}
