package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 管理端的工单查询条件。
 * <p>
 * 与用户侧「我的工单」不共用：那边只有状态一维（一个人同时开着的工单就几条），
 * 管理侧面对全库，每一维筛选都是客服分诊的实际需要。
 */
@Data
public class AdminTicketQuery {

    /** 工单号，或标题关键词 —— 客服接电话时手上可能只有其中一个 */
    private String keyword;
    /** 按提交人筛选：某个用户反复来单时，先看他之前提过什么 */
    private String userId;
    /** 见 {@code TicketStatus}；取值非法时 400，不静默忽略 */
    private String status;
    /** 见 {@code TicketCategory}；取值非法时 400 */
    private String category;

    /**
     * 按球权分诊，<b>两个取值都只在未关闭的工单里筛</b>：
     * {@code true} = 球在用户侧、等着客服回；{@code false} = 球在客服侧
     * （含从没有任何人回复过的），即"还欠着的"。
     * <p>
     * 已关闭的工单两边都不出现：关掉的工单没有球权可言，把它算进任何一侧都是噪声。
     * 传 {@code null}（不传）则不按球权筛，含已关闭。
     */
    private Boolean awaitingAdmin;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdTo;

    private Integer page = 0;
    private Integer size = 20;

    /** 对外页码从 0 开始；不要拿它直接构造 MyBatis-Plus 的 Page，用 {@link #mpCurrent()} */
    public int zeroBasedPage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** MyBatis-Plus 的页码从 1 开始。转换只留这一个出处 */
    public long mpCurrent() {
        return zeroBasedPage() + 1L;
    }

    /** 上限 100：不设上限的话 size=100000 一个请求就能把整库捞出来 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
