package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;

import java.util.List;

/**
 * 售后。
 * <p>
 * 所有面向用户的入口都要求 {@code userId}：售后单是私有数据，越权必须在服务层拦死。
 * 审核入口单独标注 —— 那是管理侧动作，没有调用方用户身份。
 */
public interface AfterSaleService {

    /**
     * 预览售后资格：能不能退、最多退多少、依据哪条政策。
     * <p>
     * 让用户在填表之前就知道结论，而不是提交完才被拒 —— 后者会让人觉得是在碰运气。
     */
    AfterSalePreview preview(String userId, Long orderItemId, String type, boolean qualityIssue);

    AfterSaleResponse apply(String userId, ApplyAfterSaleRequest request);

    /**
     * 审核。管理侧动作，**没有用户身份**，因此不对外暴露成用户接口。
     *
     * @param approved true 通过；false 驳回（此时 remark 是驳回理由，必填）
     */
    AfterSaleResponse audit(Long afterSaleId, boolean approved, String remark);

    /** 用户撤销申请。已进入退款中的不允许撤销 */
    AfterSaleResponse cancel(String userId, Long afterSaleId);

    List<AfterSaleResponse> listByUser(String userId);

    AfterSaleResponse detail(String userId, Long afterSaleId);
}
