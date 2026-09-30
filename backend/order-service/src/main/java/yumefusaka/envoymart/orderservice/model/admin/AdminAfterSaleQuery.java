package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 管理端的售后列表查询条件。
 * <p>
 * 与订单列表同样的理由单独成对象：用户侧「我的售后」不带任何条件。
 * <p>
 * <b>{@code status} 支持逗号分隔的多个值</b>（如 {@code APPLIED,APPROVED}）：
 * 售后工作台上真正要看的从来不是某一个状态，而是「待我处理的」——
 * 那对应的是两三个状态的并集。让前端为此发多次请求，翻页就没法做了。
 */
@Data
public class AdminAfterSaleQuery {

    /** 售后单号 / 订单号 / 用户 id，任一命中即可 */
    private String keyword;
    private String userId;
    /** 单个状态，或多个用逗号分隔 */
    private String status;
    /** REFUND_ONLY / RETURN_REFUND / EXCHANGE */
    private String type;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime appliedFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime appliedTo;

    private Integer page = 0;
    private Integer size = 20;

    public int safePage() {
        return page == null || page < 0 ? 0 : page;
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
     * 空白项直接丢掉而不是当成一个状态值：{@code ?status=APPLIED,} 这种尾巴上的逗号
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
