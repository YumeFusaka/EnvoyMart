package yumefusaka.envoymart.orderservice.model;

import lombok.Builder;
import lombok.Data;

/**
 * 售后政策判定结果。
 * <p>
 * 带 {@code docRef} 是有意的：结论由规则引擎给出（涉及金额与时间窗，模型算错就是资损），
 * 而「为什么」要能指向知识库里的政策原文。<b>规则给结论，检索给依据</b>——
 * 只给结论用户不服，只给原文又没法直接回答「我这种情况到底能不能退」。
 */
@Data
@Builder
public class PolicyDecision {

    private boolean allowed;
    /** 不允许时的原因，直接给用户看 */
    private String reason;
    /** 允许时最多能退多少（分） */
    private Long maxRefundAmount;
    /** 依据的政策文档编号，用于溯源 */
    private String docRef;
    /** 附加条件说明 */
    private String requirements;
    /** 命中的政策 id，便于追溯是哪条规则起的作用 */
    private Long policyId;

    public static PolicyDecision allow(Long maxRefundAmount, String docRef,
                                       String requirements, Long policyId) {
        return PolicyDecision.builder()
                .allowed(true)
                .maxRefundAmount(maxRefundAmount)
                .docRef(docRef)
                .requirements(requirements)
                .policyId(policyId)
                .build();
    }

    public static PolicyDecision reject(String reason, String docRef) {
        return PolicyDecision.builder().allowed(false).reason(reason).docRef(docRef).build();
    }
}
