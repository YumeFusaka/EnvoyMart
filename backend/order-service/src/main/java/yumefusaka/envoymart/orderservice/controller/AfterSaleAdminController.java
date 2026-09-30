package yumefusaka.envoymart.orderservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleQuery;
import yumefusaka.envoymart.orderservice.service.AfterSaleService;

/**
 * 售后的管理接口。
 * <p>
 * <b>它取代了原先 {@code /after-sales/internal/} 下的三个接口</b>（审核、确认收到退货、
 * 重试退款）。那三个接口按设计是服务间通道 —— 网关一律 404，谁也进不来，
 * 因此它们<b>从不记录操作人</b>，也没有任何一层在判定「你有没有资格批这笔退款」。
 * 管理台需要的恰恰是这两样，所以搬到这里：走网关拿身份、{@link RequireAdmin} 判角色、
 * 操作人一路传到流水里。
 * <p>
 * <b>为什么不两条路都留</b>：同一次状态流转有两个入口，迟早有人从错的那个进，
 * 而从错的那个进不会有任何症状 —— 流水里少一列而已，没人会当场发现。
 */
@RequireAdmin
@RestController
@RequestMapping("/after-sales/admin")
public class AfterSaleAdminController {

    private final AfterSaleService afterSaleService;

    public AfterSaleAdminController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    /**
     * 售后工作台列表。
     * <p>
     * {@code status} 支持逗号分隔的多个值（{@code ?status=APPLIED,APPROVED}）：
     * 真正要看的从来不是某一个状态，而是「待我处理的」——那对应的是两三个状态的并集。
     */
    @GetMapping("/after-sales")
    public Result<PageResult<AfterSaleResponse>> list(AdminAfterSaleQuery query) {
        return Result.success(afterSaleService.adminPage(query));
    }

    /** 详情：售后单 + 完整审核流水（谁在什么时候把它推到了哪一步） */
    @GetMapping("/after-sales/{id}")
    public Result<AdminAfterSaleDetail> detail(@PathVariable("id") Long id) {
        return Result.success(afterSaleService.adminDetail(id));
    }

    /**
     * 审核。
     * <p>
     * 用 {@code POST} 而不是 {@code PUT}：它不是「把某个字段改成某个值」，
     * 而是一次<b>不可重复执行的决策</b> —— 第二次提交同一个请求会被状态机拒绝
     * （单据已不在待审核），这正是想要的语义。
     */
    @PostMapping("/after-sales/{id}/audit")
    public Result<AfterSaleResponse> audit(
            @PathVariable("id") Long id,
            @RequestParam("approved") boolean approved,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(afterSaleService.audit(id, approved, remark, operatorId));
    }

    /** 确认收到退货并打款。真实流程里由商家收货动作触发 */
    @PostMapping("/after-sales/{id}/received")
    public Result<AfterSaleResponse> received(
            @PathVariable("id") Long id,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(afterSaleService.confirmReceived(id, operatorId));
    }

    /**
     * 重试退款 —— 用于「退款失败后停在退款中」的售后单。
     * <p>
     * 没有这个按钮，退款失败的单子会永远停在退款中，而用户的钱也永远退不回去。
     */
    @PostMapping("/after-sales/{id}/retry-refund")
    public Result<AfterSaleResponse> retryRefund(
            @PathVariable("id") Long id,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(afterSaleService.retryRefund(id, operatorId));
    }
}
