package yumefusaka.envoymart.reviewservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端的评价详情：列表那一行 + 完整的图片列表。
 * <p>
 * 列表上只给 {@code imageCount}（一行「3 张图」就够了），要看图的时候才走这里。
 * 两者用同一份 {@link AdminReviewSummary}，不另抄一遍字段 —— 否则「管理端能看到
 * 哪些字段」迟早演化出第二份定义。
 */
@Data
@Builder
public class AdminReviewDetail {

    private AdminReviewSummary review;
    private List<String> images;
}
