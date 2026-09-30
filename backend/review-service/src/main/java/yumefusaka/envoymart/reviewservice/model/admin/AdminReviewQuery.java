package yumefusaka.envoymart.reviewservice.model.admin;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 管理端的评价列表查询条件。
 * <p>
 * 公开侧只有一个入口「按商品列评价」，那是买家在看某个商品的口碑；
 * 运营要看的是另一组问题：谁写的、几分、有没有被隐藏、<b>哪些还没回复</b>。
 * 后两个条件公开侧根本不存在，所以单独成对象。
 */
@Data
public class AdminReviewQuery {

    private Long spuId;
    private Long skuId;
    private Long orderId;
    private String userId;
    /** 1-5，精确匹配 */
    private Integer rating;
    /** 单个状态，或多个用逗号分隔（如 {@code PENDING,PUBLISHED}） */
    private String status;
    /** 按评价内容模糊匹配 */
    private String keyword;

    /**
     * true 只看已回复、false 只看未回复，null 不筛。
     * <p>
     * 用三态而不是「一个 replied 开关」：运营打开评价管理，第一眼要的是
     * <b>待回复队列</b>（false），而「已回复的在哪」同样是个真实诉求。
     * 只有布尔开关时，默认值无论取哪边都会让另一半变成需要绕路才能看到的视图。
     */
    private Boolean hasReply;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdTo;

    private Integer page = 0;
    private Integer size = 20;

    /**
     * 对外页码，<b>从 0 开始</b>——与 {@code PageResult.page}、前端「第 N 页」一致。
     * <p>
     * <b>不要拿它直接构造 MyBatis-Plus 的 {@code Page}</b>：那边 {@code current} 从 1 开始，
     * 且 {@code offset()} 对 {@code current <= 1} 一律返回 0，于是第 0 页与第 1 页查出
     * 同一批数据、之后整体后移一页，最后一页永远取不到。要传给 MP 用 {@link #mpCurrent()}。
     */
    public int zeroBasedPage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** MyBatis-Plus 的页码从 1 开始。这步转换只留这一个出处，免得各调用点各自 +1 */
    public long mpCurrent() {
        return zeroBasedPage() + 1L;
    }

    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    /**
     * 拆成状态列表。
     * <p>
     * 空白项直接丢掉而不是当成一个状态值：{@code ?status=HIDDEN,} 这种尾巴上的逗号
     * 是前端拼串时最容易多出来的东西，让它变成一次「无匹配」的空结果，
     * 会让人以为筛选生效了、只是没有数据。
     */
    public List<String> statusList() {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
