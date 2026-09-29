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
import yumefusaka.envoymart.orderservice.model.AfterSalePreview;
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
     * 审核 —— 管理侧动作，网关已屏蔽，只有服务间或运维直连可达。
     */
    @PostMapping("/internal/{id}/audit")
    public Result<AfterSaleResponse> audit(
            @PathVariable("id") Long id,
            @RequestParam("approved") boolean approved,
            @RequestParam(value = "remark", required = false) String remark) {
        return Result.success(afterSaleService.audit(id, approved, remark));
    }

    /** 确认收到退货并打款。真实流程由商家收货触发 */
    @PostMapping("/internal/{id}/received")
    public Result<AfterSaleResponse> received(@PathVariable("id") Long id) {
        return Result.success(afterSaleServiceImpl.confirmReceived(id));
    }
}
