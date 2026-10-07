<script setup lang="ts">
import { getGroundingReport } from '@/api/eval'
import type { GroundingLiveCase, GroundingLiveRun, GroundingMetrics } from '@/api/eval'
import ErrorState from '@/components/ui/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import { computed, onMounted, ref } from 'vue'

const run = ref<GroundingLiveRun | null>(null)
const loading = ref(true)
const failed = ref(false)
const caseFilter = ref('')
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
  if (caseFilter.value === 'ISSUE') return cases.filter(issue)
  if (caseFilter.value) return cases.filter((item) => item.outcome?.kind === caseFilter.value)
  return cases
})
function summary(item: GroundingLiveCase) {
  if (item.error) return `调用失败：${item.error}`
  const outcome = item.outcome
  if (!outcome) return '未生成判定结果'
  const parts: string[] = []
  if (outcome.unsupported > 0) parts.push(`未支撑 ${outcome.unsupported}/${outcome.factSentences} 句`)
  if (outcome.wrongCitations > 0) parts.push(`引用指错 ${outcome.wrongCitations} 处`)
  if (outcome.outOfRange > 0) parts.push(`越界引用 ${outcome.outOfRange} 次`)
  if (outcome.kind === 'UNANSWERABLE') parts.push(outcome.refusalCorrect ? '拒答判定正确' : '拒答判定错误')
  if (outcome.kind === 'MULTI_HOP') parts.push(outcome.multiHopHit ? '多跳命中' : '多跳未完整命中')
  return parts.join(' · ') || '无异常'
}
</script>

<template>
  <div class="quality-page">
    <header class="quality-header"><p class="eyebrow">Production Answer Quality</p><h1>回答质量评测</h1><p class="subcopy">这份报告展示真实 Agent 在当前知识库、向量库和线上知识图谱上的生成效果。所有指标和逐条回答都来自已经完成的实测快照，不使用离线重放数据。</p></header>
    <ErrorState v-if="failed" message="真实链路回答评测快照加载失败，请重试" :on-retry="load" />
    <el-skeleton v-else-if="loading" :rows="8" animated />
    <template v-else-if="run?.status === 'COMPLETED' && metrics">
      <section class="snapshot-meta"><span>完成于 <b>{{ formatDateTime(run.finishedAt) }}</b></span><span>完成 {{ run.completedCases }} / {{ run.totalCases }} 条</span><span v-if="run.failedCases">调用失败 {{ run.failedCases }} 条</span><span class="readonly">只读快照</span></section>
      <section class="metric-grid">
        <article class="metric-card metric-card--hero"><p>幻觉率</p><strong>{{ pct(metrics.hallucinationRate) }}%</strong><small>{{ metrics.unsupportedSentences }} / {{ metrics.factSentences }} 句未被证据支撑</small></article>
        <article class="metric-card"><p>引用准确率</p><strong>{{ pct(metrics.citationAccuracy) }}%</strong><small>{{ metrics.wrongCitations }} 条错误引用</small></article>
        <article class="metric-card"><p>拒答准确率</p><strong>{{ pct(metrics.refusalAccuracy) }}%</strong><small>漏拒 {{ metrics.missedRefusals }} · 过拒 {{ metrics.overRefusals }}</small></article>
        <article class="metric-card"><p>多跳命中率</p><strong>{{ pct(metrics.multiHopHitRate) }}%</strong><small>{{ metrics.multiHopCases }} 条多跳问题</small></article>
      </section>
      <section class="cases-panel"><header class="section-head section-head--row"><div><h2>逐条真实回答</h2><p>共 {{ run.cases.length }} 条，展示问题、模型回答和确定性判定结果。</p></div><el-radio-group v-model="caseFilter" size="small"><el-radio-button value="">全部</el-radio-button><el-radio-button value="ISSUE">仅有问题</el-radio-button><el-radio-button value="ANSWERABLE">该答</el-radio-button><el-radio-button value="MULTI_HOP">多跳</el-radio-button><el-radio-button value="UNANSWERABLE">该拒</el-radio-button></el-radio-group></header><ol class="case-list"><li v-for="(item, index) in filteredCases" :key="index" class="case-row" :class="{ issue: issue(item) }"><div class="case-row__head"><span>{{ item.outcome?.id ?? '—' }}</span><b>{{ item.outcome?.kind ?? '失败' }}</b><span>{{ item.latencyMs }}ms</span></div><p class="question">{{ item.question }}</p><p v-if="item.error" class="error">{{ summary(item) }}</p><p v-else class="answer">{{ item.answer }}</p><p class="verdict">{{ summary(item) }}</p></li></ol></section>
    </template>
    <section v-else class="empty-state"><h2>暂无真实链路快照</h2><p>当前服务还没有生成可展示的实测结果。页面不会用离线重放数据填充。</p></section>
  </div>
</template>

<style scoped>
.quality-page { max-width: var(--layout-content-max); margin: 0 auto; padding: var(--ys-space-8); display: grid; gap: var(--ys-space-5); }.quality-header h1 { margin: 4px 0; font-size: var(--ys-font-2xl); }.eyebrow { margin: 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }.subcopy { max-width: 900px; margin: 0; color: var(--color-text-secondary); line-height: 1.8; }.snapshot-meta, .cases-panel, .empty-state { background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); box-shadow: var(--card-shadow); }.snapshot-meta { display: flex; flex-wrap: wrap; gap: var(--ys-space-4); padding: var(--ys-space-4) var(--ys-space-5); color: var(--color-text-secondary); font-size: var(--ys-font-sm); }.readonly { color: var(--color-success); font-weight: 600; }.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ys-space-4); }.metric-card { padding: var(--ys-space-5); background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); }.metric-card--hero { border-top: 3px solid var(--color-primary); }.metric-card p { margin: 0; color: var(--color-text-secondary); }.metric-card strong { display: block; margin: var(--ys-space-2) 0; font-size: 2rem; font-variant-numeric: tabular-nums; }.metric-card small, .section-head p { color: var(--color-text-muted); font-size: var(--ys-font-xs); }.cases-panel { padding: var(--ys-space-5); }.section-head { margin-bottom: var(--ys-space-4); }.section-head h2 { margin: 0; font-size: var(--ys-font-lg); }.section-head p { margin: 4px 0 0; }.section-head--row { display: flex; align-items: end; justify-content: space-between; gap: var(--ys-space-4); }.case-list { display: grid; gap: var(--ys-space-3); margin: 0; padding: 0; list-style: none; }.case-row { padding: var(--ys-space-4); border: 1px solid var(--color-border); border-radius: var(--card-radius); }.case-row.issue { border-color: var(--color-danger); }.case-row__head { display: flex; gap: var(--ys-space-3); color: var(--color-text-muted); font-size: var(--ys-font-xs); }.question { margin: var(--ys-space-2) 0; font-weight: 600; }.answer { margin: 0; color: var(--color-text-secondary); white-space: pre-wrap; }.verdict { margin: var(--ys-space-3) 0 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); }.error { color: var(--color-danger); }.empty-state { padding: var(--ys-space-8); text-align: center; }.empty-state h2 { margin: 0 0 var(--ys-space-2); }.empty-state p { margin: 0; color: var(--color-text-secondary); }@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.section-head--row { align-items: stretch; flex-direction: column; } }@media (max-width: 560px) { .metric-grid { grid-template-columns: 1fr; } }
</style>
