package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.ConflictReporter.Conflict;
import yumefusaka.envoymart.agent.rag.ConflictReporter.Report;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突裁定留存的契约。
 * <p>
 * 这个功能做错的形态只有一种：<b>「上次判过」变成「这次不判」</b>。
 * 资料改了之后旧裁定还命中，真实分歧被静默吞掉，而日志里一切正常。
 * 所以这里最要紧的一条是「证据内容变化后必须重新判定」，
 * 它一旦失守，整批留存就从「省一次调用」退化成「丢一个结论」。
 */
class ConflictVerdictServiceTest {

    private static DocumentChunk chunk(String content) {
        return DocumentChunk.builder().content(content).build();
    }

    private static Report reportOf(Conflict... conflicts) {
        return new Report("正文", List.of(conflicts));
    }

    private final InMemoryConflictVerdictStore store = new InMemoryConflictVerdictStore();
    private final ConflictVerdictService service = new ConflictVerdictService(store);

    @Test
    void 同一条冲突第二次出现时沿用裁定并注明时间() {
        List<DocumentChunk> evidence = List.of(chunk("每日上限 2000IU"), chunk("每日上限 4000IU"));
        Conflict conflict = new Conflict(List.of(1, 2), "以条目 1 为准，凭来源等级", true);

        service.apply(reportOf(conflict), evidence);

        Report second = service.apply(reportOf(conflict), evidence);

        assertThat(second.conflicts()).hasSize(1);
        assertThat(second.conflicts().getFirst().detail())
                .startsWith("（已于")
                .contains("裁定）")
                .contains("以条目 1 为准，凭来源等级")
                .as("沿用旧结论不该丢掉它原来的依据")
                .doesNotContain("仍待人工确认");
        assertThat(second.conflicts().getFirst().resolved()).isTrue();
    }

    /** 本批最重要的一条：证据正文改一个字，旧裁定必须作废。 */
    @Test
    void 证据内容变化后重新裁定而不是沿用旧结论() {
        Conflict conflict = new Conflict(List.of(1, 2), "以条目 1 为准", true);
        service.apply(reportOf(conflict), List.of(chunk("每日上限 2000IU"), chunk("每日上限 4000IU")));

        // 第二份资料改了数字 —— 这是「资料更新后结论要跟着变」的最小复现
        Report after = service.apply(reportOf(conflict),
                List.of(chunk("每日上限 2000IU"), chunk("每日上限 3000IU")));

        assertThat(after.conflicts().getFirst().detail())
                .as("内容变了就不该命中旧裁定")
                .isEqualTo("以条目 1 为准")
                .doesNotContain("已于");
    }

    @Test
    void 待人工确认的裁定沿用但措辞不写成裁定() {
        List<DocumentChunk> evidence = List.of(chunk("A 说 3 天"), chunk("B 说 7 天"));
        Conflict conflict = new Conflict(List.of(1, 2), "两份说明书权威度相同、版本相近，需人工确认", false);

        service.apply(reportOf(conflict), evidence);
        Report second = service.apply(reportOf(conflict), evidence);

        assertThat(second.conflicts().getFirst().resolved()).isFalse();
        assertThat(second.conflicts().getFirst().detail())
                .as("没定夺的分歧不能被说成「已裁定」——用户会当它已经有结论")
                .startsWith("（已于")
                .contains("核对，仍待人工确认）")
                .contains("需人工确认")
                .doesNotContain("裁定）");
    }

    @Test
    void 没有冲突时不产生记录() {
        service.apply(new Report("正文", List.of()), List.of(chunk("A"), chunk("B")));

        assertThat(store.find(ConflictVerdictStore.fingerprint(List.of("A", "B")))).isEmpty();
    }

    @Test
    void 引用编号为空时不留存() {
        Conflict noRefs = new Conflict(List.of(), "模型只描述了内容、没写条目编号", true);
        service.apply(reportOf(noRefs), List.of(chunk("A"), chunk("B")));

        assertThat(store.find(ConflictVerdictStore.fingerprint(List.of("A")))).isEmpty();
        assertThat(store.find(ConflictVerdictStore.fingerprint(List.of("B")))).isEmpty();
        assertThat(store.find("")).isEmpty();
    }
}
