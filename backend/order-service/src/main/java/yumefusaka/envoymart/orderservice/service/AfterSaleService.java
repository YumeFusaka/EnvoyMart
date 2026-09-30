package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.orderservice.model.AfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleQuery;

import java.util.List;

/**
 * 售后。
 * <p>
 * 所有面向用户的入口都要求 {@code userId}：售后单是私有数据，越权必须在服务层拦死。
 * 管理侧入口（{@code admin*} 与审核/收货/重试退款）反过来<b>要求 {@code operatorId}</b> ——
 * 它们做的每一件事都要落进流水，而流水的价值全在「谁干的」那一列。
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
     * 审核。管理侧动作。
     *
     * @param approved   true 通过；false 驳回（此时 remark 是驳回理由，必填）
     * @param operatorId 审核人，写进售后流水 —— 审核是这个系统里**唯一一个
     *                   由人决定钱去哪**的地方，没有操作人的流水等于没有流水
     */
    AfterSaleResponse audit(Long afterSaleId, boolean approved, String remark, String operatorId);

    /** 确认收到退货并打款。真实流程里由商家收货触发 */
    AfterSaleResponse confirmReceived(Long afterSaleId, String operatorId);

    /**
     * 重试退款。用于「退款失败后停在退款中」的售后单。
     * <p>
     * <b>必须有这个入口</b>：退款失败时状态故意不回滚（把状态退回去会让「已审核通过」
     * 这个事实消失），于是那些单子会停在退款中等人处理。没有重试入口的话，
     * 它们就永远停在那里，而用户的钱也永远退不回去。
     */
    AfterSaleResponse retryRefund(Long afterSaleId, String operatorId);

    /** 用户撤销申请。已进入退款中的不允许撤销 */
    AfterSaleResponse cancel(String userId, Long afterSaleId);

    /**
     * 用户寄回退货，录入物流单号。
     * <p>
     * <b>这是退货退款链路曾经缺的那一环</b>：审核通过后售后单停在「待寄回」，
     * 而没有任何接口能把它推到「退货中」—— 后续的商家收货、退款全都到不了。
     * 没有它，所有需要寄回的售后单都是一条死路。
     */
    AfterSaleResponse shipBack(String userId, Long afterSaleId, String carrier, String trackingNo);

    List<AfterSaleResponse> listByUser(String userId);

    /** 用户侧详情：售后单 + 流转流水（时间线）。流水里的操作人 id 已抹掉 */
    AfterSaleDetail detail(String userId, Long afterSaleId);

    // ==================== 管理侧 ====================

    /** 管理列表：按状态（可多选）、类型、用户、单号、申请时间范围筛选 */
    PageResult<AfterSaleResponse> adminPage(AdminAfterSaleQuery query);

    /** 管理详情：售后单本体 + 完整审核流水 */
    AdminAfterSaleDetail adminDetail(Long afterSaleId);
}
