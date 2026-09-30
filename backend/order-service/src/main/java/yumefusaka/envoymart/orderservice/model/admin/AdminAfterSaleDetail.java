package yumefusaka.envoymart.orderservice.model.admin;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;

import java.util.List;

/**
 * 管理端售后详情。
 * <p>
 * 售后单主体直接嵌 {@link AfterSaleResponse}（买家侧同一个类型），管理端独有的流水加在外面。
 * 与订单详情同一个理由：复制一份字段，两份就会开始漂移。
 */
@Data
@Builder
public class AdminAfterSaleDetail {

    private AfterSaleResponse afterSale;

    /** 审核流水。**「谁批的」只在这里** —— 表上那几列审计时不够用 */
    private List<StatusLogView> logs;
}
