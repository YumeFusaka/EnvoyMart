package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.contract.LogisticsResponse;
import yumefusaka.envoymart.contract.OrderResponse;

import java.util.List;

/**
 * 管理端订单详情。
 * <p>
 * <b>订单主体直接嵌 {@link OrderResponse}，不复制一份字段</b>：复制出来的那 25 个字段
 * 会立刻开始各自漂移——订单加一个字段，买家能看到、运营看不到，
 * 而没人会注意到。嵌套之后「管理端看到的订单」与「买家看到的订单」在类型上就是同一份东西。
 * <p>
 * 管理端独有的部分（备注、流水、履约）加在外面。
 */
@Data
@Builder
public class AdminOrderDetail {

    private OrderResponse order;

    /** 商家备注。与买家的 {@code order.remark} 是两个字段：覆盖买家的留言会让那句
     *  「请放门口」永久消失，而这正是售后争议里唯一能证明买家说过什么的东西 */
    private String adminRemark;

    /** 状态流水，按时间正序。运营问「这单为什么是这个状态」，答案在这里，不在客服记忆里 */
    private List<StatusLogView> statusLogs;

    /** 履约与轨迹。未发货时为 null */
    private LogisticsResponse delivery;
}
