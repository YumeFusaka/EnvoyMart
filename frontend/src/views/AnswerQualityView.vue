<script setup lang="ts">
import { getGroundingReport, runGroundingEval } from '@/api/eval'
import { useUserStore } from '@/stores'
import { ElMessage } from 'element-plus'
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
const userStore = useUserStore()
const triggering = ref(false)
const isAdmin = computed(() => userStore.profile?.roleName === 'ADMIN')
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
async function triggerEval() {
  if (triggering.value || !isAdmin.value) return
  triggering.value = true
  try {
    run.value = await runGroundingEval()
    ElMessage.success('回答质量评测已启动，页面将轮询进度')
    await load()
  } finally { triggering.value = false }
}
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
  document.getElementById(`cite-eval-${item.outcome?.id}-${index}`)?.scrollIntoView({ block: 'center' })
}
function missingAnchors(item: GroundingLiveCase, check: { anchors: string[]; refs: number[] }) {
  const normalize = (text: string) => text.replace(/\s/g, '').toLowerCase()
  return check.anchors.filter((anchor) => !check.refs.some((ref) => {
    const evidence = item.evidence?.[ref - 1]
    return evidence && normalize(evidence.content).includes(normalize(anchor))
  }))
}
</script>

<template>
  <div class="quality-page">
    <header class="quality-header"><p class="eyebrow">Production Answer Quality</p><h1>回答质量评测</h1><p class="subcopy">这份报告展示真实 Agent 在当前知识库、向量库和线上知识图谱上的生成效果。所有指标和逐条回答都来自已经完成的实测快照，不使用离线重放数据。</p><button v-if="isAdmin" class="run-button" type="button" :disabled="triggering || run?.status === 'RUNNING'" @click="triggerEval">{{ run?.status === 'RUNNING' ? '评测运行中' : triggering ? '正在启动' : '手动触发回答质量评测' }}</button></header>
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
      <section class="cases-panel"><header class="section-head section-head--row"><div><h2>逐条真实回答</h2><p>共 {{ run.cases.length }} 条，展示问题、模型回答和确定性判定结果。</p></div><el-radio-group v-model="caseFilter" size="small"><el-radio-button value="">全部</el-radio-button><el-radio-button value="ISSUE">仅有问题</el-radio-button><el-radio-button value="ANSWERABLE">该答</el-radio-button><el-radio-button value="MULTI_HOP">多跳</el-radio-button><el-radio-button value="UNANSWERABLE">该拒</el-radio-button></el-radio-group></header><ol class="case-list"><li v-for="(item, index) in filteredCases" :key="index" class="case-row" :class="{ issue: issue(item) }"><div class="case-row__head"><span>{{ item.outcome?.id ?? '—' }}</span><b>{{ item.outcome?.kind ?? '失败' }}</b><span>{{ item.latencyMs }}ms</span></div><p class="question">{{ item.question }}</p><p v-if="item.error" class="error">{{ summary(item) }}</p><MessageContent v-else :content="item.answer ?? ''" :citation-count="item.evidence?.length ?? 0" @cite="(number) => showEvidence(item, number)" /><CitationList v-if="item.evidence?.length" :items="item.evidence" :list-id="`eval-${item.outcome?.id}`" /><p v-else class="verdict">此历史快照未保存证据切片，引用编号无法追溯；不能用当前语料替代当次依据。</p><details class="trace-details"><summary>查看完整执行链路</summary><section class="trace-grid"><h3>工具轨迹</h3><div v-if="item.toolExecutions?.length"><p v-for="tool in item.toolExecutions" :key="`${tool.tool}-${tool.latencyMs}`">{{ tool.tool }}：{{ tool.success ? (tool.noData ? '执行后无结果' : '成功') : '失败' }} · {{ tool.latencyMs }}ms</p></div><p v-else>该快照未记录工具执行；不能用当前运行数据补齐。</p><h3>检索与扩写</h3><p>最终查询：{{ item.retrievalQuery || '未记录' }}</p><p v-if="item.retrievalTrace">候选：向量 {{ item.retrievalTrace.vectorCandidates }} · BM25 {{ item.retrievalTrace.keywordCandidates }} · 图谱 {{ item.retrievalTrace.graphCandidates }} · 融合 {{ item.retrievalTrace.fusedCandidates }} · 重排 {{ item.retrievalTrace.rerankApplied ? '已执行' : '未执行' }}</p><p v-else>检索中间链路：该历史快照未记录</p><p v-if="item.expansion">扩写：HyDE {{ item.expansion.hypothetical || '无' }}；角度 {{ item.expansion.angles?.join(' / ') || '无' }}</p><p v-else>扩写：未记录或本轮未产出</p><h3>元数据</h3><p>耗时 {{ item.latencyMs }}ms · evidence {{ item.evidence?.length ?? 0 }} 条 · requestId {{ '历史快照未记录' }}</p></section></details><details v-if="checksOf(item, 'MISSING').length || checksOf(item, 'WRONG_TARGET').length || checksOf(item, 'OUT_OF_RANGE').length" class="problem-details" open><summary>定位问题句</summary><section v-if="checksOf(item, 'MISSING').length" class="problem-group problem-group--missing"><h3>未支撑句（{{ checksOf(item, 'MISSING').length }}）</h3><ul><li v-for="check in checksOf(item, 'MISSING')" :key="`missing-${check.sentence}`">{{ check.sentence }}<small>未找到有效句内引用或段落引用，无法定位支撑该句的当次证据。</small></li></ul></section><section v-if="checksOf(item, 'WRONG_TARGET').length" class="problem-group problem-group--wrong"><h3>引用指错句（{{ checksOf(item, 'WRONG_TARGET').length }}）</h3><ul><li v-for="check in checksOf(item, 'WRONG_TARGET')" :key="`wrong-${check.sentence}`">{{ check.sentence }}<small>当前引用：{{ check.refs.map((ref) => `[${ref}]`).join(' ') }}；引用证据未逐字覆盖的标注词：{{ missingAnchors(item, check).join('、') || '需对照证据确认' }}（逐字检查，不等于医学事实判错）。请对照上面的原文切片核查。</small></li></ul></section><section v-if="checksOf(item, 'OUT_OF_RANGE').length" class="problem-group problem-group--wrong"><h3>越界引用句（{{ checksOf(item, 'OUT_OF_RANGE').length }}）</h3><ul><li v-for="check in checksOf(item, 'OUT_OF_RANGE')" :key="`range-${check.sentence}`">{{ check.sentence }}</li></ul></section></details><p class="verdict">{{ summary(item) }}</p></li></ol></section>
    </template>
    <section v-else-if="run?.status === 'FAILED'" class="empty-state"><h2>真实评测未获得有效回答</h2><p>{{ run.failedCases }} 条模型调用失败或返回降级回复，本次没有可展示的质量指标。需要恢复模型服务后采集真实快照。</p></section>
    <section v-else class="empty-state"><h2>暂无真实链路快照</h2><p>当前服务还没有生成可展示的实测结果。页面不会用离线重放数据填充。</p></section>
  </div>
</template>

<style scoped>
.quality-page { max-width: var(--layout-content-max); margin: 0 auto; padding: var(--ys-space-8); display: grid; gap: var(--ys-space-5); }.run-button { margin-top: var(--ys-space-3); min-height: 40px; padding: 0 var(--ys-space-4); border: 0; border-radius: var(--ys-radius-sm); color: var(--color-text-inverse); background: var(--color-primary); font-weight: 700; cursor: pointer; }.run-button:disabled { cursor: wait; opacity: .6; }.quality-header h1 { margin: 4px 0; font-size: var(--ys-font-2xl); }.eyebrow { margin: 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }.subcopy { max-width: 900px; margin: 0; color: var(--color-text-secondary); line-height: 1.8; }.snapshot-meta, .cases-panel, .empty-state, .root-cause-panel { background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); box-shadow: var(--card-shadow); }.snapshot-meta { display: flex; flex-wrap: wrap; gap: var(--ys-space-4); padding: var(--ys-space-4) var(--ys-space-5); color: var(--color-text-secondary); font-size: var(--ys-font-sm); }.readonly { color: var(--color-success); font-weight: 600; }.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ys-space-4); }.metric-card { padding: var(--ys-space-5); background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); }.metric-card--hero { border-top: 3px solid var(--color-primary); }.metric-card p { margin: 0; color: var(--color-text-secondary); }.metric-card strong { display: block; margin: var(--ys-space-2) 0; font-size: 2rem; font-variant-numeric: tabular-nums; }.metric-card small, .section-head p { color: var(--color-text-muted); font-size: var(--ys-font-xs); }.root-cause-panel { display: grid; gap: var(--ys-space-3); padding: var(--ys-space-4) var(--ys-space-5); }.root-cause-panel h2 { margin: 0; font-size: var(--ys-font-lg); }.root-cause-panel p { margin: 4px 0 0; color: var(--color-text-muted); font-size: var(--ys-font-xs); }.root-cause-list { display: flex; flex-wrap: wrap; gap: var(--ys-space-2); }.root-cause-list button { display: inline-flex; align-items: center; gap: var(--ys-space-2); min-height: 32px; padding: 0 var(--ys-space-3); color: var(--color-text-secondary); background: var(--color-bg-subtle); border: 1px solid var(--color-border); border-radius: var(--card-radius); cursor: pointer; }.root-cause-list button:hover, .root-cause-list button.active { color: var(--color-primary-strong); border-color: var(--color-primary); background: var(--color-primary-soft); }.root-cause-list b { font-variant-numeric: tabular-nums; }.cases-panel { padding: var(--ys-space-5); }.section-head { margin-bottom: var(--ys-space-4); }.section-head h2 { margin: 0; font-size: var(--ys-font-lg); }.section-head p { margin: 4px 0 0; }.section-head--row { display: flex; align-items: end; justify-content: space-between; gap: var(--ys-space-4); }.case-list { display: grid; gap: var(--ys-space-3); margin: 0; padding: 0; list-style: none; }.case-row { padding: var(--ys-space-4); border: 1px solid var(--color-border); border-radius: var(--card-radius); }.case-row.issue { border-color: var(--color-danger); }.case-row__head { display: flex; gap: var(--ys-space-3); color: var(--color-text-muted); font-size: var(--ys-font-xs); }.question { margin: var(--ys-space-2) 0; font-weight: 600; }.answer { margin: 0; color: var(--color-text-secondary); white-space: pre-wrap; }.problem-details { margin-top: var(--ys-space-3); border: 1px solid color-mix(in srgb, var(--color-danger) 24%, var(--color-border)); border-radius: var(--card-radius); background: color-mix(in srgb, var(--color-danger) 5%, var(--color-bg-surface)); }.problem-details summary { cursor: pointer; padding: var(--ys-space-3); color: var(--color-danger); font-weight: 700; }.problem-group { padding: 0 var(--ys-space-3) var(--ys-space-3); }.problem-group h3 { margin: var(--ys-space-2) 0; font-size: var(--ys-font-sm); }.problem-group ul { margin: 0; padding-inline-start: var(--ys-space-5); }.problem-group li { margin-block: var(--ys-space-2); color: var(--color-text-secondary); white-space: pre-wrap; }.problem-group small { display: block; margin-top: var(--ys-space-1); color: var(--color-text-muted); }.problem-group--missing h3 { color: var(--color-warning-strong); }.problem-group--wrong h3 { color: var(--color-danger); }.verdict { margin: var(--ys-space-3) 0 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); }.error { color: var(--color-danger); }.empty-state { padding: var(--ys-space-8); text-align: center; }.empty-state h2 { margin: 0 0 var(--ys-space-2); }.empty-state p { margin: 0; color: var(--color-text-secondary); }@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.section-head--row { align-items: stretch; flex-direction: column; } }@media (max-width: 560px) { .metric-grid { grid-template-columns: 1fr; } }
</style>
