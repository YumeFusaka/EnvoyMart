package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import yumefusaka.envoymart.agent.rag.ConflictReporter.Conflict;
import yumefusaka.envoymart.agent.rag.ConflictReporter.Report;
import yumefusaka.envoymart.agent.rag.ConflictVerdictStore.Verdict;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 冲突裁定的留存与复用 —— 把 {@link ConflictReporter} 抽出的冲突接上
 * {@link ConflictVerdictStore}。
 * <p>
 * <b>为什么单独一个类，而不是塞进 {@code ConflictReporter}。</b>那个类做的是「从模型输出里
 * 抽出冲突」，是纯函数（输入文本、输出结构），它被单测直接钉住；而这里要碰存储、
 * 处理「查不到 / 查到了 / 存储炸了」三条分支。把存储的失败模式引进一个纯函数，
 * 会让那个类既难测又难读。
 * <p>
 * <b>最重要的性质：指纹由「这一轮实际用到的那几段证据正文」算出，不由模型输出的文字算。</b>
 * 模型的措辞每轮都会变（「说法不一致」/「存在差异」），按文字算指纹会让同一条冲突
 * 每次都得到新指纹，留存就永远不会命中——一个「实现了但没生效」的功能，
 * 而它在日志里看起来一切正常。
 * <p>
 * <b>失效是自动的。</b>文档改一个字，证据正文变、指纹变、旧裁定查不到，于是重新走一次
 * 模型判定。这正是本批最要紧的断言：<b>内容哈希变化必须使旧裁定失效</b>，
 * 否则「上次判过」会变成「这次不判」，把真实分歧静默吞掉。
 */
@Slf4j
public class ConflictVerdictService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ConflictVerdictStore store;

    public ConflictVerdictService(ConflictVerdictStore store) {
        this.store = store;
    }

    /**
     * 给这一轮的冲突补上「已于 X 时裁定」的说明，并把新裁定落库。
     * <p>
     * 传入的 {@code evidence} 是本轮注入给模型的证据切片（与 {@code [n]} 角标一一对应），
     * 用来按 {@code refs} 取正文算指纹。取不到编号时该条不参与留存——
     * <b>宁可反复判定，也不要在没有身份的情况下认领一条旧裁定</b>：
     * 认错等于拿另一场冲突的结论回答这一次的问题。
     *
     * @param report   抽取结果（也许来自上一轮存下来的裁定）
     * @param evidence 本轮证据切片，1 基角标对应 {@code evidence.get(n-1)}
     * @return 可能与入参一样，也可能带着「已于 X 时裁定」的说明
     */
    public Report apply(Report report, List<DocumentChunk> evidence) {
        if (report == null || report.conflicts().isEmpty() || evidence == null || evidence.isEmpty()) {
            return report;
        }
        List<Conflict> annotated = new ArrayList<>(report.conflicts().size());
        for (Conflict conflict : report.conflicts()) {
            String fingerprint = fingerprintOf(conflict, evidence);
            if (fingerprint.isEmpty()) {
                // 认不出涉及的证据：照常展示，但本就不该留存（没有身份就没有过期判据）
                annotated.add(conflict);
                continue;
            }
            Optional<Verdict> known = find(fingerprint);
            if (known.isPresent()) {
                annotated.add(reuse(conflict, known.get()));
                continue;
            }
            annotated.add(conflict);
            // 两态都留存，但**回显的措辞不同**：已定夺的说「已于 X 时裁定」，
            // 待人工确认的说「已于 X 时核对，仍待人工确认」。
            // 不存后者的代价是「同一条分歧视而不见地问十次、判十一次」——
            // 而它每次的结论都是「定不了」，那十一次模型调用买不到任何新信息。
            // 措辞必须分开，否则用户会以为「已于 X 时裁定」= 已经有了说法
            save(fingerprint, conflict);
        }
        return new Report(report.reply(), List.copyOf(annotated));
    }

    /**
     * 复用一条旧裁定：结论沿用，并注明是哪一刻定的。
     * <p>
     * 两态措辞必须分开：已定夺说「裁定」，待人工确认说「核对…仍待人工确认」。
     * 都用「裁定」会让一个还没有结论的分歧看起来像已经有了结论 ——
     * 而用户不会去点开卡片核对，他只会记住「平台说过以哪份为准」。
     */
    private Conflict reuse(Conflict conflict, Verdict verdict) {
        String stamp = STAMP.format(Instant.ofEpochMilli(verdict.decidedAt()));
        String prefix = verdict.resolved()
                ? "（已于 " + stamp + " 裁定）"
                : "（已于 " + stamp + " 核对，仍待人工确认）";
        return new Conflict(conflict.refs(), prefix + verdict.detail(), verdict.resolved());
    }

    private Optional<Verdict> find(String fingerprint) {
        try {
            return store.find(fingerprint);
        } catch (RuntimeException e) {
            // 存储故障不能把这一轮对话带下去：退回「没查到」，重新判定一次即可
            log.warn("[Conflict] 查询历史裁定失败，本轮按首次判定处理：{}", e.getMessage());
            return Optional.empty();
        }
    }

    private void save(String fingerprint, Conflict conflict) {
        try {
            store.save(new Verdict(fingerprint, conflict.detail(), conflict.resolved(),
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            // 存不上只影响「下次还要再判一遍」，不影响这一次的回答
            log.warn("[Conflict] 留存裁定失败：{}", e.getMessage());
        }
    }

    /**
     * 按冲突涉及的证据编号算出指纹。
     * <p>
     * 编号可能为空（模型没写「条目 N」，只描述了内容）。那时返回空串，
     * 调用方据此跳过留存。<b>不拿冲突文字本身当指纹</b>——措辞每轮都在变，
     * 用它会得到一个永远命不中的存储。
     */
    private String fingerprintOf(Conflict conflict, List<DocumentChunk> evidence) {
        List<String> contents = new ArrayList<>();
        for (Integer ref : conflict.refs()) {
            int index = ref - 1;
            if (index >= 0 && index < evidence.size()) {
                contents.add(evidence.get(index).getContent());
            }
        }
        return ConflictVerdictStore.fingerprint(contents);
    }
}