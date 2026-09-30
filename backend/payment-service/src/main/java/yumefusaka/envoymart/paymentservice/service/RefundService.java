package yumefusaka.envoymart.paymentservice.service;

import java.util.List;
import yumefusaka.envoymart.contract.RefundResponse;

public interface RefundService {

    /**
     * 订单相关操作触发的退款（售后审核通过、或已支付订单被关闭）。
     * <p>
     * 没有调用方身份：它表达的是「这笔订单的钱要还回去」，不是「某个用户在操作」。
     * 因此**不对外暴露成 HTTP 接口**，只供服务间调用。
     * <p>
     * 这是全项目唯一的退款写入口。曾经还有一条用户侧入口（用户直接对自己的支付单退款），
     * 它只校验支付单归属、不经过售后政策引擎与状态机，已删除——退款必须有闸门。
     */
    RefundResponse refundForOrder(Long orderId, Long afterSaleId, Long amount, String reason);

    List<RefundResponse> listByOrder(String userId, Long orderId);
}
