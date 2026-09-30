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
import yumefusaka.envoymart.orderservice.model.AfterSaleDetail;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.ApplyAfterSaleRequest;
import yumefusaka.envoymart.orderservice.model.ShipBackRequest;
import yumefusaka.envoymart.orderservice.service.AfterSaleService;

import java.util.List;

/**
 * 售后的用户侧接口。
 * <p>
 * 管理侧动作<b>不在这里</b>：审核、确认收到退货、重试退款已经搬到
 * {@link AfterSaleAdminController}（{@code /after-sales/admin}）。
 * 它们原先以 {@code /internal/} 的形式挂在本控制器下 —— 那是服务间通道，
 * 网关一律 404，因此既不记录审核人、也只能靠「进不来」当作授权。
 * 用户能自己审核通过自己的退款申请，这套流程就没有意义了，所以这两件事必须分开两条路。
 */
@RestController
@RequestMapping("/after-sales")
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    public AfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
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

    /** 详情带流转流水（时间线）：「我的退货到哪一步了」的唯一完整答案 */
    @GetMapping("/{id}")
    public Result<AfterSaleDetail> detail(
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

    /** 寄回退货：审核通过后由用户录入物流单号，售后再往下走 */
    @PostMapping("/{id}/ship-back")
    public Result<AfterSaleResponse> shipBack(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody ShipBackRequest request) {
        return Result.success(afterSaleService.shipBack(userId, id, request.getCarrier(), request.getTrackingNo()));
    }

}
