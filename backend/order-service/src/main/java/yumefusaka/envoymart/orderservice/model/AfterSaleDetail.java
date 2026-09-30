package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.orderservice.model.admin.StatusLogView;

import java.util.List;

/**
 * 用户侧售后详情：售后单本体 + 完整流转流水。
 * <p>
 * 流水是「我的退货现在到哪一步了」这个问题唯一完整的答案：只看当前状态，
 * 用户知道「退货中」，但不知道什么时候通过审核的、什么时候该寄回 ——
 * 而那正是他下一步要做的动作。
 * <p>
 * 流水的 {@code operatorId} 在这里会被抹掉：管理端操作人对用户显示为「平台」，
 * 把账号 id 透给用户既无意义，也平白多一个可枚举的内部标识。
 */
@Data
@Builder
public class AfterSaleDetail {

    private AfterSaleResponse afterSale;

    private List<StatusLogView> logs;
}
