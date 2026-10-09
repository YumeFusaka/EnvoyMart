<script setup lang="ts">
import { getGroundingReport } from '@/api/eval'
import type { GroundingLiveCase, GroundingLiveRun, GroundingMetrics } from '@/api/eval'
import ErrorState from '@/components/ui/ErrorState.vue'
import MessageContent from '@/components/ai/MessageContent.vue'
import CitationList from '@/components/ai/CitationList.vue'
import { formatDateTime } from '@/utils/format'
import { computed, onMounted, ref } from 'vue'

const run = ref<GroundingLiveRun | null>(null)
const loading = ref(true)
const failed = ref(false)
const caseFilter = ref('')
const rootCauseFilter = ref('')
const causeLabels: Record<string, string> = {
  WRONG_CITATION: '引用未指向支持该事实的证据',
  UNSUPPORTED_CLAIM: '事实句缺少可核对的证据',
  GRAPH_PATH_OR_GROUNDING: '多跳回答存在证据覆盖问题',
  OVER_REFUSAL: '标注可回答，但证据门判为不足',
  MISSED_REFUSAL: '标注应拒答，但证据门未拦截',
}
async function load() {
  loading.value = true
  failed.value = false
  try { run.value = (await getGroundingReport()).live } catch { failed.value = true } finally { loading.value = false }
}
onMounted(load)
const pct = (value: number) => (value * 100).toFixed(1)
const metrics = computed<GroundingMetrics | null>(() => run.value?.metrics ?? null)
const issue = (item: GroundingLiveCase) => !item.outcome || item.outcome.unsupported > 0 || item.outcome.wrongCitations > 0 || item.outcome.outOfRange > 0 || !item.outcome.refusalCorrect || (item.outcome.kind === 'MULTI_HOP' && !item.outcome.multiHopHit)
const filteredCases = computed(() => {
  const cases = run.value?.cases ?? []
  return cases.filter((item) => {
    const kindMatch = caseFilter.value === 'ISSUE'
      ? issue(item)
      : !caseFilter.value || item.outcome?.kind === caseFilter.value
    const rootMatch = !rootCauseFilter.value || item.outcome?.rootCause === rootCauseFilter.value
    return kindMatch && rootMatch
  })
})
const rootCauses = computed(() => {
  const counts = new Map<string, number>()
  for (const item of run.value?.cases ?? []) {
    const cause = item.outcome?.rootCause
    if (cause && cause !== 'NONE') counts.set(cause, (counts.get(cause) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])
})
function summary(item: GroundingLiveCase) {
  if (item.error) return `调用失败：${item.error}`
  const outcome = item.outcome
  if (!outcome) return '未生成判定结果'
  const parts: string[] = []
  if (outcome.unsupported > 0) parts.push(`未支撑 ${outcome.unsupported}/${outcome.factSentences} 句`)
  if (outcome.wrongCitations > 0) parts.push(`引用指错 ${outcome.wrongCitations} 处`)
  if (outcome.outOfRange > 0) parts.push(`越界引用 ${outcome.outOfRange} 次`)
  if (outcome.kind === 'UNANSWERABLE') parts.push(outcome.refusalCorrect ? '证据门判定正确' : '证据门判定错误')
  if (outcome.kind === 'MULTI_HOP') parts.push(outcome.multiHopHit ? '多跳命中' : '多跳未完整命中')
  if (outcome.rootCause && outcome.rootCause !== 'NONE') parts.push(causeLabels[outcome.rootCause] ?? outcome.rootCause)
  return parts.join(' · ') || '无异常'
}
function checksOf(item: GroundingLiveCase, verdict: string) {
  return item.outcome?.citations?.filter((check) => check.verdict === verdict) ?? []
}
function showEvidence(item: GroundingLiveCase, index: number) {
  const evidenceId = `cite-eval-${item.outcome?.id}-${index}`
  document.getElementById(evidenceId)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  document.getElementById(evidenceId)?.classList.add('is-active')
}
function missingAnchors(item: GroundingLiveCase, check: { anchors: string[]; refs: number[] }) {
  const normalize = (text: string) => text.replace(/\s/g, '').toLowerCase()
  return check.anchors.filter((anchor) => !check.refs.some((ref) => {
    const evidence = item.evidence?.[ref - 1]
    return evidence && normalize(evidence.content).includes(normalize(anchor))
  }))
}
function citationReason(item: GroundingLiveCase, check: { refs: number[]; anchors: string[] }, verdict: string) {
  if (verdict === 'MISSING') return '判定原因：句子中没有可用引用，或所在段落的引用编号均无效，因此无法把事实句绑定到本次返回的证据。'
  if (verdict === 'OUT_OF_RANGE') return `判定原因：引用编号 ${check.refs.map((ref) => `[${ref}]`).join('、') || '缺失'} 超出本次证据范围（共 ${item.evidence?.length ?? 0} 条），无法定位对应资料。`
  const missing = missingAnchors(item, check)
  return `判定原因：该句引用的证据未逐字覆盖标注锚点${missing.length ? `「${missing.join('、')}」` : '中的关键内容'}。这是确定性文本核验结果，不等于医学事实判错。`
}
function jumpToEvidence(item: GroundingLiveCase, refs: number[]) {
  const first = refs.find((ref) => ref > 0 && ref <= (item.evidence?.length ?? 0))
  if (first) showEvidence(item, first)
}
</script>

<template>
  <div class="quality-page">
    <header class="quality-header"><p class="eyebrow">Production Answer Quality</p><h1>回答质量评测</h1><p class="subcopy">这份报告展示真实 Agent 在当前知识库、向量库和线上知识图谱上的生成效果。所有指标和逐条回答都来自管理员手动触发后生成的实测快照。</p></header>
    <ErrorState v-if="failed" message="真实链路回答评测快照加载失败，请重试" :on-retry="load" />
    <el-skeleton v-else-if="loading" :rows="8" animated />
    <template v-else-if="run?.status === 'COMPLETED' && metrics">
      <section class="snapshot-meta"><span>完成于 <b>{{ formatDateTime(run.finishedAt) }}</b></span><span>完成 {{ run.completedCases }} / {{ run.totalCases }} 条</span><span v-if="run.failedCases">调用失败 {{ run.failedCases }} 条</span><span>Prompt {{ run.promptVersion ?? '—' }}</span><span>Pipeline {{ run.pipelineVersion ?? '—' }}</span><span class="readonly">只读快照</span></section>
      <section class="metric-grid">
        <article class="metric-card metric-card--hero"><p>幻觉率</p><strong>{{ pct(metrics.hallucinationRate) }}%</strong><small>{{ metrics.unsupportedSentences }} / {{ metrics.factSentences }} 句未被证据支撑</small></article>
        <article class="metric-card"><p>引用准确率</p><strong>{{ pct(metrics.citationAccuracy) }}%</strong><small>{{ metrics.wrongCitations }} 条错误引用</small></article>
        <article class="metric-card"><p>证据门判定准确率</p><strong>{{ pct(metrics.refusalAccuracy) }}%</strong><small>未拦截低相关证据 {{ metrics.missedRefusals }} · 误拦截可答问题 {{ metrics.overRefusals }}</small></article>
        <article class="metric-card"><p>多跳命中率</p><strong>{{ pct(metrics.multiHopHitRate) }}%</strong><small>{{ metrics.multiHopCases }} 条多跳问题</small></article>
      </section>
      <section v-if="rootCauses.length" class="root-cause-panel">
        <div><h2>问题根因分布</h2><p>按判定层分类，点击后只查看对应 bad case。</p></div>
        <div class="root-cause-list">
          <button v-for="([cause, count]) in rootCauses" :key="cause" type="button" :class="{ active: rootCauseFilter === cause }" @click="rootCauseFilter = rootCauseFilter === cause ? '' : cause">
            <span>{{ causeLabels[cause] ?? cause }}</span><b>{{ count }}</b>
          </button>
        </div>
      </section>
      <section class="cases-panel">
        <header class="section-head section-head--row">
          <div><h2>逐条真实回答</h2><p>共 {{ run.cases.length }} 条，展示问题、模型回答和确定性判定结果。</p></div>
          <el-radio-group v-model="caseFilter" size="small"><el-radio-button value="">全部</el-radio-button><el-radio-button value="ISSUE">仅有问题</el-radio-button><el-radio-button value="ANSWERABLE">该答</el-radio-button><el-radio-button value="MULTI_HOP">多跳</el-radio-button><el-radio-button value="UNANSWERABLE">该拒</el-radio-button></el-radio-group>
        </header>
        <ol class="case-list">
          <li v-for="(item, index) in filteredCases" :key="index" class="case-row" :class="{ issue: issue(item) }">
            <div class="case-row__head"><span>{{ item.outcome?.id ?? '—' }}</span><b>{{ item.outcome?.kind ?? '失败' }}</b><span>{{ item.latencyMs }}ms</span></div>
            <p class="question">{{ item.question }}</p>
            <p v-if="item.error" class="error">{{ summary(item) }}</p>
            <MessageContent v-else :content="item.answer ?? ''" :citation-count="item.evidence?.length ?? 0" @cite="(number) => showEvidence(item, number)" />
            <CitationList v-if="item.evidence?.length" :items="item.evidence" :list-id="`eval-${item.outcome?.id}`" />
            <p v-else class="verdict">本次快照没有可展示的证据切片，引用编号无法追溯；不能用当前语料替代当次依据。</p>
            <details class="trace-details">
              <summary>查看完整执行链路</summary>
              <div class="trace-grid">
                <section class="trace-stage"><h3><span>01</span> 查询与检索</h3>
                  <dl><dt>最终查询</dt><dd>{{ item.retrievalTrace?.query || item.retrievalQuery || '历史快照未采集查询链路' }}<small v-if="item.retrievalTrace?.query === item.question || item.retrievalQuery === item.question">原句检索，未发生指代改写</small></dd></dl>
                  <div v-if="item.retrievalTrace" class="candidate-counts"><span>向量 <b>{{ item.retrievalTrace.vectorCandidates }}</b></span><span>BM25 <b>{{ item.retrievalTrace.keywordCandidates }}</b></span><span>图谱 <b>{{ item.retrievalTrace.graphCandidates }}</b></span><span>融合 <b>{{ item.retrievalTrace.fusedCandidates }}</b></span></div>
                  <dl v-if="item.retrievalTrace"><dt>重排</dt><dd>{{ item.retrievalTrace.rerankApplied ? `已执行 · ${item.retrievalTrace.rerankedCandidates} 条` : '未执行' }}</dd><dt>重排查询</dt><dd>{{ item.retrievalTrace.rerankQuery || '本轮未提供' }}</dd></dl>
                  <p v-else class="trace-empty">历史快照未采集中间候选，不能推断哪一路检索失效。</p>
                </section>
                <section class="trace-stage"><h3><span>02</span> 查询扩写</h3>
                  <dl v-if="item.expansion"><dt>假想答案</dt><dd class="expansion-text">{{ item.expansion.hypothetical || '本轮未产出' }}</dd><dt>角度改写</dt><dd><ul class="angles"><li v-for="angle in item.expansion.angles" :key="angle">{{ angle }}</li></ul><span v-if="!item.expansion.angles?.length">本轮未产出</span></dd></dl>
                  <p v-else class="trace-empty">{{ item.retrievalTrace ? '本轮未产生有效扩写' : '历史快照未采集扩写过程' }}</p>
                </section>
                <section class="trace-stage"><h3><span>03</span> 工具执行</h3>
                  <ol v-if="item.toolExecutions?.length" class="tool-trace"><li v-for="(tool, toolIndex) in item.toolExecutions" :key="`${tool.tool}-${toolIndex}`"><div><code>{{ tool.tool }}</code><span class="tool-state" :class="{ failed: !tool.success }">{{ tool.success ? (tool.noData ? '无结果' : '成功') : '失败' }}</span><span>{{ tool.latencyMs }}ms</span></div><details v-if="tool.input || tool.output"><summary>调用详情</summary><pre v-if="tool.input">{{ tool.input }}</pre><p v-if="tool.output">{{ tool.output }}</p></details></li></ol>
                  <p v-else class="trace-empty">{{ item.requestId ? '本轮未调用工具' : '历史快照未采集工具执行' }}</p>
                </section>
                <section class="trace-stage trace-stage--metadata"><h3><span>04</span> 快照与证据</h3><dl><dt>总耗时</dt><dd>{{ item.latencyMs }} ms</dd><dt>返回证据</dt><dd>{{ item.evidenceCount }} 条</dd><dt>请求标识</dt><dd><code>{{ item.requestId || '历史快照未采集请求标识' }}</code></dd><dt>判定方式</dt><dd>{{ item.outcome?.mode === 'PER_SENTENCE' ? '逐句引用核验' : item.outcome?.mode === 'WHOLE_UNGROUNDED' ? '整段无依据判定' : '无需进行事实句核验' }}</dd></dl></section>
              </div>
            </details>
            <details v-if="checksOf(item, 'MISSING').length || checksOf(item, 'WRONG_TARGET').length || checksOf(item, 'OUT_OF_RANGE').length" class="problem-details" open>
              <summary>定位问题句</summary>
              <section v-for="verdict in ['MISSING', 'WRONG_TARGET', 'OUT_OF_RANGE']" v-show="checksOf(item, verdict).length" :key="verdict" class="problem-group" :class="verdict === 'MISSING' ? 'problem-group--missing' : 'problem-group--wrong'">
                <h3>{{ verdict === 'MISSING' ? '缺少支撑证据' : verdict === 'WRONG_TARGET' ? '引用与事实不匹配' : '引用编号越界' }}（{{ checksOf(item, verdict).length }}）</h3>
                <ul><li v-for="check in checksOf(item, verdict)" :key="`${verdict}-${check.sentence}`"><blockquote>{{ check.sentence }}</blockquote><small>{{ citationReason(item, check, verdict) }}</small><div v-if="check.refs.length" class="problem-refs"><span>该句引用</span><button v-for="ref in check.refs" :key="ref" type="button" :disabled="ref < 1 || ref > (item.evidence?.length ?? 0)" :title="ref > 0 && ref <= (item.evidence?.length ?? 0) ? '定位到本次引用证据' : '该编号没有对应证据'" @click="jumpToEvidence(item, [ref])">[{{ ref }}] {{ item.evidence?.[ref - 1]?.title || '无对应证据' }}</button></div></li></ul>
              </section>
            </details>
            <p class="verdict">{{ summary(item) }}</p>
          </li>
        </ol>
      </section>
    </template>
    <section v-else-if="run?.status === 'FAILED'" class="empty-state"><h2>真实评测未获得有效回答</h2><p>{{ run.failedCases }} 条模型调用失败或返回降级回复，本次没有可展示的质量指标。需要恢复模型服务后采集真实快照。</p></section>
    <section v-else class="empty-state"><h2>暂无真实链路快照</h2><p>当前服务还没有生成可展示的实测结果。页面不会用离线重放数据填充。</p></section>
  </div>
</template>

<style scoped>
.quality-page { max-width: var(--layout-content-max); margin: 0 auto; padding: var(--ys-space-8); display: grid; gap: var(--ys-space-5); }
.run-button { margin-top: var(--ys-space-3); min-height: 40px; padding: 0 var(--ys-space-4); border: 0; border-radius: var(--ys-radius-sm); color: var(--color-text-inverse); background: var(--color-primary); font-weight: 700; cursor: pointer; }
.run-button:disabled { cursor: wait; opacity: .6; }
.quality-header h1 { margin: 4px 0; font-size: var(--ys-font-2xl); }
.eyebrow { margin: 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 700; text-transform: uppercase; }
.subcopy { max-width: 900px; margin: 0; color: var(--color-text-secondary); line-height: 1.8; }
.snapshot-meta, .cases-panel, .empty-state, .root-cause-panel { background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); box-shadow: var(--card-shadow); }
.snapshot-meta { display: flex; flex-wrap: wrap; gap: var(--ys-space-4); padding: var(--ys-space-4) var(--ys-space-5); color: var(--color-text-secondary); font-size: var(--ys-font-sm); }
.readonly { color: var(--color-success); font-weight: 600; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ys-space-4); }
.metric-card { padding: var(--ys-space-5); background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); }
.metric-card--hero { border-top: 3px solid var(--color-primary); }
.metric-card p { margin: 0; color: var(--color-text-secondary); }
.metric-card strong { display: block; margin: var(--ys-space-2) 0; font-size: 2rem; font-variant-numeric: tabular-nums; }
.metric-card small, .section-head p { color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.root-cause-panel { display: grid; gap: var(--ys-space-3); padding: var(--ys-space-4) var(--ys-space-5); }
.root-cause-panel h2 { margin: 0; font-size: var(--ys-font-lg); }
.root-cause-panel p { margin: 4px 0 0; color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.root-cause-list { display: flex; flex-wrap: wrap; gap: var(--ys-space-2); }
.root-cause-list button { display: inline-flex; align-items: center; gap: var(--ys-space-2); min-height: 32px; padding: 0 var(--ys-space-3); color: var(--color-text-secondary); background: var(--color-bg-subtle); border: 1px solid var(--color-border); border-radius: var(--card-radius); cursor: pointer; }
.root-cause-list button:hover, .root-cause-list button.active { color: var(--color-primary-strong); border-color: var(--color-primary); background: var(--color-primary-soft); }
.root-cause-list b { font-variant-numeric: tabular-nums; }
.cases-panel { padding: var(--ys-space-5); }
.section-head { margin-bottom: var(--ys-space-4); }
.section-head h2 { margin: 0; font-size: var(--ys-font-lg); }
.section-head p { margin: 4px 0 0; }
.section-head--row { display: flex; align-items: end; justify-content: space-between; gap: var(--ys-space-4); }
.case-list { display: grid; gap: var(--ys-space-3); margin: 0; padding: 0; list-style: none; }
.case-row { min-width: 0; padding: var(--ys-space-4); border: 1px solid var(--color-border); border-radius: var(--card-radius); }
.case-row.issue { border-inline-start: 3px solid var(--color-danger); }
.case-row__head { display: flex; flex-wrap: wrap; gap: var(--ys-space-3); color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.question { margin: var(--ys-space-2) 0; font-weight: 600; }
.trace-details { margin-top: var(--ys-space-4); border: 1px solid var(--color-border); border-radius: var(--card-radius); background: var(--color-bg-subtle); }
.trace-details > summary { padding: var(--ys-space-3) var(--ys-space-4); color: var(--color-text-primary); font-weight: 700; cursor: pointer; }
.trace-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--ys-space-3); padding: 0 var(--ys-space-3) var(--ys-space-3); }
.trace-stage { min-width: 0; padding: var(--ys-space-4); background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--card-radius); }
.trace-stage h3 { display: flex; align-items: center; gap: var(--ys-space-2); margin: 0 0 var(--ys-space-3); font-size: var(--ys-font-sm); }
.trace-stage h3 span { color: var(--color-primary-strong); font-variant-numeric: tabular-nums; }
.trace-stage dl { display: grid; grid-template-columns: minmax(90px, auto) minmax(0, 1fr); gap: var(--ys-space-2) var(--ys-space-3); margin: 0; font-size: var(--ys-font-xs); }
.trace-stage dt { color: var(--color-text-muted); }
.trace-stage dd { min-width: 0; margin: 0; color: var(--color-text-secondary); overflow-wrap: anywhere; }
.trace-stage dd small { display: block; margin-top: var(--ys-space-1); color: var(--color-text-muted); }
.candidate-counts { display: flex; flex-wrap: wrap; gap: var(--ys-space-2); margin: var(--ys-space-3) 0; }
.candidate-counts span { display: inline-flex; gap: var(--ys-space-1); padding: var(--ys-space-1) var(--ys-space-2); background: var(--color-bg-subtle); border-radius: var(--ys-radius-sm); color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.candidate-counts b { color: var(--color-text-primary); font-variant-numeric: tabular-nums; }
.trace-empty { margin: 0; color: var(--color-text-muted); font-size: var(--ys-font-xs); line-height: 1.6; }
.expansion-text { white-space: pre-wrap; }
.angles { margin: 0; padding-inline-start: var(--ys-space-4); }
.tool-trace { display: grid; gap: var(--ys-space-2); margin: 0; padding: 0; list-style: none; }
.tool-trace > li { padding: var(--ys-space-2); background: var(--color-bg-subtle); border-radius: var(--ys-radius-sm); }
.tool-trace > li > div { display: flex; flex-wrap: wrap; align-items: center; gap: var(--ys-space-2); color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.tool-trace code, .trace-stage code { overflow-wrap: anywhere; color: var(--color-text-primary); }
.tool-state { color: var(--color-success); }
.tool-state.failed { color: var(--color-danger); }
.tool-trace details { margin-top: var(--ys-space-2); font-size: var(--ys-font-xs); }
.tool-trace pre { max-height: 180px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.trace-stage--metadata { grid-column: 1 / -1; }
.problem-details { margin-top: var(--ys-space-3); border: 1px solid color-mix(in srgb, var(--color-danger) 24%, var(--color-border)); border-radius: var(--card-radius); background: color-mix(in srgb, var(--color-danger) 5%, var(--color-bg-surface)); }
.problem-details > summary { cursor: pointer; padding: var(--ys-space-3); color: var(--color-danger); font-weight: 700; }
.problem-group { padding: 0 var(--ys-space-3) var(--ys-space-3); }
.problem-group h3 { margin: var(--ys-space-2) 0; font-size: var(--ys-font-sm); }
.problem-group ul { display: grid; gap: var(--ys-space-3); margin: 0; padding-inline-start: var(--ys-space-5); }
.problem-group li { color: var(--color-text-secondary); }
.problem-group blockquote { margin: 0; padding-inline-start: var(--ys-space-3); border-inline-start: 2px solid var(--color-danger); white-space: pre-wrap; }
.problem-group small { display: block; margin-top: var(--ys-space-2); color: var(--color-text-muted); line-height: 1.6; }
.problem-refs { display: flex; flex-wrap: wrap; align-items: center; gap: var(--ys-space-2); margin-top: var(--ys-space-2); color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.problem-refs button { min-height: 28px; padding: 0 var(--ys-space-2); color: var(--color-primary-strong); background: var(--color-bg-surface); border: 1px solid var(--color-border); border-radius: var(--ys-radius-sm); cursor: pointer; }
.problem-refs button:hover:not(:disabled) { border-color: var(--color-primary); background: var(--color-primary-soft); }
.problem-refs button:disabled { opacity: .55; cursor: not-allowed; }
.problem-group--missing h3 { color: var(--color-warning-strong); }
.problem-group--wrong h3 { color: var(--color-danger); }
.verdict { margin: var(--ys-space-3) 0 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); }
.error { color: var(--color-danger); }
.empty-state { padding: var(--ys-space-8); text-align: center; }
.empty-state h2 { margin: 0 0 var(--ys-space-2); }
.empty-state p { margin: 0; color: var(--color-text-secondary); }
:deep(.citation.is-active) { outline: 2px solid var(--color-primary); outline-offset: 2px; }
@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.section-head--row { align-items: stretch; flex-direction: column; } }
@media (max-width: 560px) { .quality-page { padding: var(--ys-space-4); }.metric-grid, .trace-grid { grid-template-columns: 1fr; }.trace-stage--metadata { grid-column: auto; }.trace-stage dl { grid-template-columns: 1fr; gap: var(--ys-space-1); }.trace-stage dd { margin-bottom: var(--ys-space-2); } }
</style>
