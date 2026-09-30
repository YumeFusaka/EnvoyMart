package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条状态流水。
 * <p>
 * 订单流水（{@code order_status_log}）与售后流水（{@code after_sale_log}）的字段完全一致，
 * 因此共用这一个视图：两处各写一份的结果是其中一处先加字段，另一处慢慢跟上，
 * 而「为什么这张单子是现在这个状态」这个问题，两个页面给出的答案会不一样。
 */
@Data
@Builder
public class StatusLogView {

    private String fromStatus;
    private String toStatus;

    /** USER / SYSTEM / ADMIN —— 区分「用户自己干的」「系统自动干的」「后台人员干的」 */
    private String operatorType;
    /** 操作人 id。系统动作没有操作人，这里是 null */
    private String operatorId;

    private String remark;
    private LocalDateTime createdAt;
}
