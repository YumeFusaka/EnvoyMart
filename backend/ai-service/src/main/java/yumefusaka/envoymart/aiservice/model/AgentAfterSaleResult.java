package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 售后申请结果 —— 只回执单号、状态与金额，供模型向用户复述。
 * <p>
 * 刻意不回整份工单实体：售后工单有十几个状态流转字段，其中多数与「我刚提交了什么」无关。
 * 模型需要的是「提交成功没有、单号是多少、退多少钱、下一步等什么」。
 */
@Data
public class AgentAfterSaleResult {

    private Long id;
    private String afterSaleNo;
    private String statusText;
    /** 预计退款金额，单位分 */
    private Long refundAmount;
    private String docRef;
}
