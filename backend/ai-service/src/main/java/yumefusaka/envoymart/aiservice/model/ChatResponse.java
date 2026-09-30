package yumefusaka.envoymart.aiservice.model;

import lombok.Builder;
import lombok.Data;
import yumefusaka.envoymart.agent.rag.EvidenceGate;
import yumefusaka.envoymart.contract.ProductSummary;

import java.util.List;

@Data
@Builder
public class ChatResponse {

    private String sessionId;
    private String reply;
    private List<KnowledgeSnippet> knowledge;
    private List<ToolCallResponse> toolCalls;
    private List<ProductSummary> recommendedProducts;

    /**
     * 等待用户确认的高危操作，前端据此渲染确认卡片。
     * <p>
     * 每项是可直接展示的中文描述，形如 {@code order_cancel(orderId=12)}——
     * 不只是工具名。用户看不到要取消的是哪一单时的「确认」，等于没确认。
     * 非空表示本轮对话<b>被中断</b>、{@code reply} 是确认提示而非回答；
     * 前端确认后带 {@code approved=true} 重发，才真正执行。
     */
    private List<String> pendingActions;

    /**
     * 本轮证据门的判定，决定 {@link #knowledge} 该被说成什么。
     * <p>
     * {@code SUFFICIENT} 才是「依据」；{@code WEAK} 表示检索到了但相关度不足，
     * 只能是「相关度不足的线索」——system prompt 那一侧对同一批切片下的正是这个结论
     * （见 {@code KnowledgePrompt} 的 weak 分支），界面若照旧标题「依据 N 条」，
     * 就等于对模型说「别信」、对用户说「这是依据」。
     * <p>
     * 判定含「重排分与余弦相似度用两把尺子」「图谱依据在场时豁免」这类内部规则，
     * 由后端算好下发，前端不重复实现。
     */
    private EvidenceGate.Level evidenceLevel;

    /**
     * 讲了一条事实却没交代出处、已从 {@link #reply} 中剔除的句子。
     * <p>
     * 剔除了却仍然下发：用户该看到「回答里少了什么、为什么少」。静默删除是更坏的选择——
     * 既没让他读到不可靠的内容，也没让他知道系统替他兜了底。
     * <p>
     * <b>有内容不等于都被剔除了</b>：整篇没有一处有效引用时，逐句剔会把回答砍碎，
     * 此时只报告不剔除，这些句子仍在 {@link #reply} 里。判定规则见 {@code CitationVerifier}。
     */
    private List<String> unsupportedClaims;

    /**
     * {@link #unsupportedClaims} 是否已被移出 {@link #reply}。
     * <p>
     * 必须与列表一起下发：<b>「已经替你拿掉了」和「还留在上面，你自己判断」是两件事</b>，
     * 用同一句话去描述会把后者说成前者——那正是这道闸最不该犯的错。
     */
    private boolean unsupportedStripped;

    /**
     * 整篇回答<b>没有任何依据</b>——没有知识库引用，也没有工具执行记录，
     * 内容是模型凭自身知识生成的。
     * <p>
     * 与 {@link #unsupportedClaims} 是两级粒度：后者点名「哪几句」讲事实没出处，
     * 是一条精准的修订；它是「这一整段都没有平台依据」，是一句免责声明。
     * 两者互斥：有引用时按句报，一个引用也没有、也没有工具执行时才整篇报。
     * <p>
     * 这个信号必须由后端下发而不能由前端推：判据涉及「本轮有没有执行过工具」，
     * 前端只看得到回答正文。
     */
    private boolean ungrounded;

    /**
     * 本轮证据之间被发现的矛盾——同一件事在不同文档里有不同说法。
     * <p>
     * 后端不让模型自己挑一个讲，而是要求它把矛盾列出来；这里把那段文字抽成结构化字段，
     * 前端才能渲染成一张能点回原文的卡片（{@link Conflict#refs()} 就是回答里
     * {@code [n]} 的 n，可直接跳转）。见 {@code ConflictReporter}。
     */
    private List<Conflict> conflicts;

    /**
     * @param refs   涉及的证据编号（1 基），可能为空——认不出编号时不猜，只展示文字
     * @param detail 冲突原文
     */
    public record Conflict(List<Integer> refs, String detail) {
    }
}
