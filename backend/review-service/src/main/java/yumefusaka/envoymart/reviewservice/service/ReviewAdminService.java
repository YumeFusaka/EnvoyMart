package yumefusaka.envoymart.reviewservice.service;

import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewDetail;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewQuery;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewSummary;

/**
 * 评价的管理端能力。
 * <p>
 * 单独一个接口而不是并进 {@link ReviewService}：那边的四个方法都以「公开发布的评价」
 * 为前提（列表与统计只查 {@code PUBLISHED}），而这里恰恰要能看见并操作非公开状态。
 * 混在一个接口里，迟早有人在公开路径上误用了可以改状态的方法。
 */
public interface ReviewAdminService {

    PageResult<AdminReviewSummary> list(AdminReviewQuery query);

    AdminReviewDetail detail(Long reviewId);

    /**
     * 改状态：{@code PENDING} / {@code PUBLISHED} / {@code HIDDEN}。
     * 设为 HIDDEN 必须给出原因，恢复时已存的隐藏记录被清空。
     */
    AdminReviewSummary changeStatus(Long reviewId, String status, String reason, String operatorId);

    /** 回复评价。{@code content} 为空表示撤回回复 */
    AdminReviewSummary reply(Long reviewId, String content, String operatorId);
}
