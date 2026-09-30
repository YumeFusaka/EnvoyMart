package yumefusaka.envoymart.productservice.model.admin;

import lombok.Builder;
import lombok.Data;

/**
 * 管理端的品牌行。
 * <p>
 * 不复用公开的 {@code BrandView}：那个只回 id / 名称 / logo，是给买家看导航用的。
 * 管理端要的是「这条记录能不能改、改了什么」，需要 status 与 description，
 * 而它们出现在公开响应里属于多余的信息暴露。
 */
@Data
@Builder
public class AdminBrand {

    private Long id;
    private String name;
    private String logo;
    private String description;
    /** 1 启用 / 0 停用 */
    private Integer status;
}
