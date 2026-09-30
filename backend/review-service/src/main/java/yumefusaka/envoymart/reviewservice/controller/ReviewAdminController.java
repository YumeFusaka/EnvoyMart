package yumefusaka.envoymart.reviewservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewDetail;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewQuery;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewSummary;
import yumefusaka.envoymart.reviewservice.model.admin.ReviewReplyRequest;
import yumefusaka.envoymart.reviewservice.model.admin.ReviewStatusRequest;
import yumefusaka.envoymart.reviewservice.service.ReviewAdminService;

/**
 * 评价的管理接口。
 * <p>
 * 路径挂在 {@code /reviews/admin} 下，而不是单开 {@code /admin/reviews} —— 网关的公开规则
 * 按前缀写、路由按前缀配（{@code /reviews/**} 已有），挂在原有前缀下路由不用动，
 * 而「管理接口不是公开资源」由网关的 {@code /admin} 段判据统一排除
 * （见 {@code JwtGatewayFilter}）。这是批次 A 定下的约定，这里跟着走。
 * <p>
 * <b>公开侧不受这套接口影响，也不需要配合改动</b>：{@code listBySpu} / {@code statistics} /
 * {@code markUseful} 本来就按 {@code status = PUBLISHED} 过滤，隐藏一条评价之后，
 * 它在买家侧自然消失。
 */
@RequireAdmin
@RestController
@RequestMapping("/reviews/admin")
public class ReviewAdminController {

    private final ReviewAdminService adminService;

    public ReviewAdminController(ReviewAdminService adminService) {
        this.adminService = adminService;
    }

    /** 评价列表：可按商品 / 用户 / 评分 / 状态 / 内容关键词 / 是否已回复 / 时间范围筛选 */
    @GetMapping("/reviews")
    public Result<PageResult<AdminReviewSummary>> list(AdminReviewQuery query) {
        return Result.success(adminService.list(query));
    }

    @GetMapping("/reviews/{id}")
    public Result<AdminReviewDetail> detail(@PathVariable("id") Long id) {
        return Result.success(adminService.detail(id));
    }

    /**
     * 改状态。设为 HIDDEN 必须带原因。
     * <p>
     * 用 {@code PUT} 而不是 {@code POST}：状态是评价上的一个字段，重复提交同一个值
     * 结果完全一样（服务层对「状态没变」直接跳过，不会刷新隐藏记录）。
     */
    @PutMapping("/reviews/{id}/status")
    public Result<AdminReviewSummary> changeStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReviewStatusRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.changeStatus(id, request.getStatus(),
                request.getReason(), operatorId));
    }

    /**
     * 回复评价，传空内容即撤回。
     * <p>
     * 回复体走请求体而不是查询参数：它是中文长文本，塞进 URL 会被各种编码层折腾一遍
     * （本项目在 Git Bash 下真的踩到过命令行参数被按 GBK 转码）。
     */
    @PutMapping("/reviews/{id}/reply")
    public Result<AdminReviewSummary> reply(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReviewReplyRequest request,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.reply(id, request.getContent(), operatorId));
    }
}
