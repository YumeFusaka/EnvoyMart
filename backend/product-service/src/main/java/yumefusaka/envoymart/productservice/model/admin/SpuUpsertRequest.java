package yumefusaka.envoymart.productservice.model.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建 / 编辑商品的一次提交。
 * <p>
 * <b>SPU、SKU、规格、参数放在同一个请求里</b>，而不是拆成四个接口分步保存：
 * 管理台上它们是同一张表单，分步保存意味着「保存到一半失败」会留下一个
 * 规格已改、SKU 没改的商品——而那个状态谁来都看不出是坏的。
 * 一个事务里全做完，要么全成要么全不成。
 */
@Data
public class SpuUpsertRequest {

    /** 留空则自动生成 */
    @Size(max = 64, message = "商品编码最长 64 个字符")
    private String spuCode;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 255, message = "商品名称最长 255 个字符")
    private String name;

    @Size(max = 255, message = "副标题最长 255 个字符")
    private String subtitle;

    @NotNull(message = "类目不能为空")
    private Long categoryId;

    private Long brandId;

    @Size(max = 512)
    private String mainImage;

    /** 轮播图地址。库里存成逗号分隔的一列，出入参用列表 */
    private List<@Size(max = 512) String> images = new ArrayList<>();

    /** 详情正文，HTML。写入前过白名单净化（见 HtmlSanitizer） */
    private String detailHtml;

    private List<@Size(max = 32) String> tags = new ArrayList<>();

    /**
     * 0 下架 / 1 上架。
     * <p>
     * 允许在新建时就填 1：先建草稿再上架要多一次操作，而草稿态的商品本来也进不了公开列表
     * （公开查询强制 {@code status = 1}）。
     */
    private Integer status = 0;

    @Valid
    private List<SkuRequest> skus = new ArrayList<>();

    @Valid
    private List<SpecRequest> specs = new ArrayList<>();

    @Valid
    private List<AttributeRequest> attributes = new ArrayList<>();
}
