package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 管理端的订单列表查询条件。
 * <p>
 * <b>为什么不复用用户侧的「我的订单」</b>：那个接口没有查询条件 ——
 * 一个人几十单，全列出来就行。管理侧面对的是全库，每一维筛选都是必须的，
 * 而且<b>它必须分页</b>。两者连返回结构都不同（管理侧要带买家与运单号），
 * 与其在一个接口上按角色分叉，不如让两条路径各自写死在各自的类型上。
 */
@Data
public class AdminOrderQuery {

    /** 订单号，或收货人姓名/手机号 —— 客服接到电话时手上只有其中一样 */
    private String keyword;
    private String userId;
    /**
     * 见 {@code OrderStatus}。取值非法时返回 400，不是空结果——
     * 一个拼错的状态值若被静默忽略，页面会一本正经地展示「全部订单」，
     * 而使用的人以为自己是筛过的。
     */
    private String status;

    /**
     * 下单时间范围，闭区间，两个都可以单独省略。
     * <p>
     * 格式写死在注解上（ISO-8601，如 {@code 2026-09-30T00:00:00}）而不是依赖框架默认：
     * 默认解析器接受什么格式随版本变化，而前端只会按一个固定格式发。
     * 格式不匹配时 Spring 抛类型转换异常 → 400，这正是想要的——
     * 静默忽略一个看不懂的时间条件，会让「筛出来的是全部订单」看起来像筛选生效了。
     */
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

    /** 上限 100，与商品管理列表同一条线：不设上限的话 `size=100000` 一个请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
