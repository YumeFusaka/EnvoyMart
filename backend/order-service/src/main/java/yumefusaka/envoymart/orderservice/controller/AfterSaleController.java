package yumefusaka.envoymart.orderservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.contract.AfterSalePreview;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;
import yumefusaka.envoymart.orderservice.service.AfterSaleService;
import yumefusaka.envoymart.orderservice.service.impl.AfterSaleServiceImpl;

import java.util.List;

/**
 * 售后。
 * <p>
 * 内部入口（{@code /internal/}）是管理侧动作，网关对该前缀一律 404 ——
 * 用户能自己审核通过自己的退款申请，那这套流程就没有意义了。
 */
@RestController
@RequestMapping("/after-sales")
public class AfterSaleController {

    private final AfterSaleService afterSaleService;
    private final AfterSaleServiceImpl afterSaleServiceImpl;

    public AfterSaleController(AfterSaleService afterSaleService,
                               AfterSaleServiceImpl afterSaleServiceImpl) {
        this.afterSaleService = afterSaleService;
        this.afterSaleServiceImpl = afterSaleServiceImpl;
    }

    /** 填表前先问「能不能退、最多退多少」，而不是提交完才被拒 */
    @GetMapping("/preview")
    public Result<AfterSalePreview> preview(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @RequestParam("orderItemId") Long orderItemId,
            @RequestParam("type") String type,
            @RequestParam(value = "qualityIssue", defaultValue = "false") boolean qualityIssue) {
        return Result.success(afterSaleService.preview(userId, orderItemId, type, qualityIssue));
    }

    @PostMapping
    public Result<AfterSaleResponse> apply(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @Valid @RequestBody ApplyAfterSaleRequest request) {
        return Result.success(afterSaleService.apply(userId, request));
    }

    @GetMapping
    public Result<List<AfterSaleResponse>> list(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(afterSaleService.listByUser(userId));
    }

    @GetMapping("/{id}")
    public Result<AfterSaleResponse> detail(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(afterSaleService.detail(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<AfterSaleResponse> cancel(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id) {
        return Result.success(afterSaleService.cancel(userId, id));
    }

    /**
     * 审核 —— 管理侧动作，写在 {@code /internal/} 下，网关对 {@code /after-sales/internal/} 一律 404。
     * <p>
     * <b>不读身份、不校验审核人</b>：它记不下「谁批的」，「同意/拒绝」这件事本身也没有任何一层在鉴权，
     * 唯一的门是网关那份排除清单。同类的洞真的出现过一次（{@code /orders/internal/{id}/ship}
     * 漏登记），现在由 {@code InternalEndpointCoverageTest} 兜住漏登记。
     * <p>
     * 后续的管理后台<b>不能复用这个接口</b>：它需要有审核人、需要有权限判定，
     * 而这条路按设计是服务间通道。管理侧入口要另开一条带 {@code @RequireAdmin} 的接口。
     */
    @PostMapping("/internal/{id}/audit")
    public Result<AfterSaleResponse> audit(
            @PathVariable("id") Long id,
            @RequestParam("approved") boolean approved,
            @RequestParam(value = "remark", required = false) String remark) {
        return Result.success(afterSaleService.audit(id, approved, remark));
    }

    /**
     * 重试退款 —— 用于「退款失败后停在退款中」的售后单。
     * <p>
     * 退款失败时状态故意不回滚，那些单子会停在退款中等人处理；
     * 没有这个入口它们就永远停在那里，用户的钱也永远退不回去。
     */
    @PostMapping("/internal/{id}/retry-refund")
    public Result<AfterSaleResponse> retryRefund(@PathVariable("id") Long id) {
        return Result.success(afterSaleServiceImpl.retryRefund(id));
    }

    /** 确认收到退货并打款。真实流程由商家收货触发 */
    @PostMapping("/internal/{id}/received")
    public Result<AfterSaleResponse> received(@PathVariable("id") Long id) {
        return Result.success(afterSaleServiceImpl.confirmReceived(id));
    }
}
