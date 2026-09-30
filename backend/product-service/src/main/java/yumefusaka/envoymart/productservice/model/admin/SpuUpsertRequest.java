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

    /**
     * 轮播图地址。库里存成逗号分隔的一列（{@code varchar(2048)}），出入参用列表。
     * <p>
     * <b>两个上限是一起算出来的，不能分开改</b>：拼接后的长度是
     * {@code 张数 × 单张长度 + (张数 - 1)}。上一版是 20 × 512 + 19 = 10259 字符，
     * 而列只有 2048 —— 校验放行、落库时 {@code Data truncation}，客户端拿到的是 500，
     * 报错信息里只有列名，看不出是这里算错了。
     * <p>
     * 现在是 10 × 200 + 9 = 2009 ≤ 2048。**动任何一个数字，另一个都要重算。**
     */
    @Size(max = 10, message = "轮播图最多 10 张")
    private List<@Size(max = 200, message = "单个图片地址最长 200 个字符") String> images = new ArrayList<>();

    /**
     * 详情正文，HTML。写入前过白名单净化（见 HtmlSanitizer）。
     * <p>
     * 长度上限是必须的：这个字段会先被读进堆，再交给 jsoup 解析成 DOM（内存放大数倍），
     * 然后才落库。没有上限时，一条请求就能把一个服务推到 OOM，而发它只需要一个能进管理台的账号。
     * {@code JsonSizeLimitConfig} 另有一道进程级的兜底，这道是接口契约本身的一部分——
     * 它给得出「详情正文最长 20 万字符」这种能照着改的提示。
     */
    @Size(max = 200_000, message = "详情正文最长 20 万字符")
    private String detailHtml;

    /** 同样受列宽约束：{@code varchar(255)}，8 × 24 + 7 = 199。改动时和 images 一样要重算 */
    @Size(max = 8, message = "标签最多 8 个")
    private List<@Size(max = 24, message = "单个标签最长 24 个字符") String> tags = new ArrayList<>();

    /**
     * 0 下架 / 1 上架。
     * <p>
     * 允许在新建时就填 1：先建草稿再上架要多一次操作，而草稿态的商品本来也进不了公开列表
     * （公开查询强制 {@code status = 1}）。
     */
    private Integer status = 0;

    /** 上限按笛卡尔积的价值定：3 个规格 × 各 20 个值就已是 8000 个组合，远超人能维护的量 */
    @Valid
    @Size(max = 500, message = "规格组合最多 500 个")
    private List<SkuRequest> skus = new ArrayList<>();

    @Valid
    @Size(max = 5, message = "规格维度最多 5 个")
    private List<SpecRequest> specs = new ArrayList<>();

    @Valid
    @Size(max = 50, message = "商品参数最多 50 个")
    private List<AttributeRequest> attributes = new ArrayList<>();
}
