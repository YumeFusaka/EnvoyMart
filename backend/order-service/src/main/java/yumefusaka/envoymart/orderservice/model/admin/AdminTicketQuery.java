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
     * 只看向客服的（最后一条消息来自用户）—— 客服上班第一件事是捞自己的欠账，
     * 而不是逐页翻。这个筛选对应列表上的 last_reply_by 列。
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
