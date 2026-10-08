<script setup lang="ts">
import { getProductionRetrievalReport } from '@/api/eval'
import type { ProductionRetrievalCase, ProductionRetrievalReport } from '@/api/eval'
import ErrorState from '@/components/ui/ErrorState.vue'
import { formatDateTime } from '@/utils/format'
import { computed, onMounted, ref } from 'vue'

const production = ref<ProductionRetrievalReport | null>(null)
const loading = ref(true)
const failed = ref(false)
const caseFilter = ref('')

async function load() {
  loading.value = true
  failed.value = false
  try { production.value = await getProductionRetrievalReport() } catch { failed.value = true } finally { loading.value = false }
}
onMounted(load)

const pct = (value: number) => (value * 100).toFixed(1)
const fixed3 = (value: number) => value.toFixed(3)
const STRATUM_META: Record<string, { color: string; desc: string }> = {
  LEXICAL: { color: 'var(--color-accent)', desc: '用户用词与资料接近' },
  PARAPHRASE: { color: 'var(--color-warning)', desc: '口语表达与资料用词不同' },
  HARD: { color: 'var(--color-danger)', desc: '需要语义理解和多路检索' },
}
const filteredCases = computed<ProductionRetrievalCase[]>(() => {
  const cases = production.value?.cases ?? []
  if (caseFilter.value === 'MISS') return cases.filter((item) => !item.hit)
  if (caseFilter.value) return cases.filter((item) => item.stratum === caseFilter.value)
  return cases
})
const missCount = computed(() => production.value?.cases.filter((item) => !item.hit).length ?? 0)
</script>

<template>
  <div class="eval-page">
    <header class="eval-header">
      <p class="eyebrow">Production Retrieval</p>
      <h1>检索质量评测</h1>
      <p class="subcopy">这份报告展示用户实际使用的检索链路：query 改写（口语改写、扩写、HyDE）→ 真实向量库、BM25、线上 Neo4j 图谱并行 → RRF → 百炼重排。数据来自当前知识库和知识图谱，页面只读取已生成的实测快照。</p>
    </header>
    <ErrorState v-if="failed" message="真实链路评测快照加载失败，请重试" :on-retry="load" />
    <el-skeleton v-else-if="loading" :rows="8" animated />
    <template v-else-if="production?.status === 'COMPLETED'">
      <section class="snapshot-meta" aria-label="实测快照信息">
        <span>生成于 <b>{{ formatDateTime(production.generatedAt!) }}</b></span>
        <span>语料 {{ production.corpus.documents }} 篇 · {{ production.corpus.cases }} 条样本</span>
        <span>耗时 {{ (production.durationMs / 1000).toFixed(1) }} 秒</span>
        <span class="snapshot-meta__readonly">只读快照</span>
      </section>
      <section class="pipeline" aria-label="真实检索链路"><div class="pipeline__label">本次实测链路</div><div class="pipeline__value">{{ production.pipeline }}</div></section>
      <section class="metric-grid" aria-label="整体指标">
        <article class="metric-card metric-card--hero"><p class="metric-card__label">Hit Rate@{{ production.overall.topK }}</p><p class="metric-card__value">{{ pct(production.overall.hitRate) }}<span>%</span></p><p class="metric-card__hint">相关文档是否进入 top-{{ production.overall.topK }}</p></article>
        <article class="metric-card"><p class="metric-card__label">MRR@{{ production.overall.topK }}</p><p class="metric-card__value">{{ fixed3(production.overall.mrr) }}</p><p class="metric-card__hint">第一篇相关文档的平均排位</p></article>
        <article class="metric-card"><p class="metric-card__label">NDCG@{{ production.overall.topK }}</p><p class="metric-card__value">{{ fixed3(production.overall.ndcg) }}</p><p class="metric-card__hint">整体排序质量</p></article>
      </section>
      <section class="pipeline" aria-label="知识图谱多跳表现"><div class="pipeline__label">知识图谱路径证据</div><div class="pipeline__value">{{ production.graphPath.hitCases }} / {{ production.graphPath.requiredCases }} 条多跳查询由图谱证据命中（{{ pct(production.graphPath.hitRate) }}%）</div></section>
      <section class="strata-panel" aria-label="分档表现">
        <header class="section-head"><h2>按问题类型</h2><p>看清真实链路在哪类问题上命中或失效。</p></header>
        <div class="strata-list"><article v-for="stratum in production.strata" :key="stratum.key" class="stratum"><div class="stratum__head"><span>{{ stratum.label }}</span><b>{{ pct(stratum.metrics.hitRate) }}%</b></div><div class="stratum__bar"><div :style="{ width: `${pct(stratum.metrics.hitRate)}%`, background: STRATUM_META[stratum.key]?.color }" /></div><p>{{ STRATUM_META[stratum.key]?.desc ?? stratum.note }}</p><small>{{ stratum.metrics.caseCount }} 条 · MRR {{ fixed3(stratum.metrics.mrr) }} · NDCG {{ fixed3(stratum.metrics.ndcg) }}</small></article></div>
      </section>
      <section class="cases-panel" aria-label="逐条明细">
        <header class="section-head section-head--row"><div><h2>逐条明细</h2><p>共 {{ production.cases.length }} 条，{{ missCount }} 条未命中；这里展示本次真实链路的完整结果。</p></div><el-radio-group v-model="caseFilter" size="small"><el-radio-button value="">全部</el-radio-button><el-radio-button value="MISS">仅未命中</el-radio-button><el-radio-button v-for="stratum in production.strata" :key="stratum.key" :value="stratum.key">{{ stratum.label }}</el-radio-button></el-radio-group></header>
        <p class="cases-count">显示 {{ filteredCases.length }} / {{ production.cases.length }} 条</p>
        <ol class="case-list"><li v-for="(item, index) in filteredCases" :key="`${item.stratum}-${index}`" class="case-row" :class="{ 'is-miss': !item.hit || (item.graphRequired && !item.graphEvidenceHit) }"><span class="case-row__rank">{{ item.hit ? `第 ${item.hitRank} 位` : '未命中' }}</span><div class="case-row__body"><p class="case-row__query">{{ item.query }}</p><p class="case-row__docs"><b>期望：</b><RouterLink v-for="id in item.relevantDocIds" :key="id" :to="{ name: 'knowledge-doc', params: { docNo: id } }">{{ item.retrievedTitles[id] ?? id }} </RouterLink></p><p class="case-row__docs"><b>实际：</b><RouterLink v-for="(id, rank) in item.retrievedDocIds" :key="id" :to="{ name: 'knowledge-doc', params: { docNo: id } }">[{{ rank + 1 }}] {{ item.retrievedTitles[id] ?? id }} </RouterLink></p><p v-if="item.graphRequired" class="case-row__docs"><b>图谱路径：</b>{{ item.graphEvidenceHit ? '命中相关图谱证据' : '未观察到相关图谱证据' }}</p><p v-if="!item.hit" class="case-row__docs"><b>错误说明：</b>最终 Top-K 未包含任何标注相关文档。这里只能定位到最终召回/排序未命中，旧快照未保存中间候选，不能据此断言是哪一路失效。</p><p v-if="item.graphRequired &amp;&amp; !item.graphEvidenceHit" class="case-row__docs"><b>图谱说明：</b>未在最终结果观察到相关图谱切片贡献；文本命中不等于图谱路径命中。</p><details class="trace-details"><summary>查看完整检索链路</summary><dl><dt>最终查询</dt><dd>{{ item.trace?.query ?? '该快照未记录' }}</dd><dt>候选阶段</dt><dd v-if="item.trace">向量 {{ item.trace.vectorCandidates }} · BM25 {{ item.trace.keywordCandidates }} · 图谱 {{ item.trace.graphCandidates }} · 融合 {{ item.trace.fusedCandidates }}</dd><dd v-else>该快照未记录中间候选</dd><dt>重排</dt><dd v-if="item.trace">{{ item.trace.rerankApplied ? `已执行（${item.trace.rerankedCandidates} 条）` : '未执行' }} · 查询：{{ item.trace.rerankQuery || '—' }}</dd><dd v-else>该快照未记录</dd><dt>扩写</dt><dd v-if="item.expansions">HyDE：{{ item.expansions.hypothetical || '无' }}；角度：{{ item.expansions.angles?.join(' / ') || '无' }}</dd><dd v-else>该快照未记录</dd><dt>证据切片</dt><dd>{{ item.evidence?.length ? `${item.evidence.length} 条已进入回答上下文` : '执行后无结果或历史快照未记录' }}</dd></dl></details></div></li></ol>
      </section>
    </template>
    <section v-else class="empty-state"><h2>暂无真实链路快照</h2><p>当前服务还没有生成可展示的实测结果。页面不会用离线夹具或伪向量数据填充。</p></section>
  </div>
</template>

<style scoped>
.eval-page { max-width: var(--layout-content-max); margin: 0 auto; padding: var(--ys-space-8); display: grid; gap: var(--ys-space-5); }
.eval-header h1 { margin: 4px 0; font-size: var(--ys-font-2xl); }
.eyebrow { margin: 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.subcopy { max-width: 900px; margin: 0; color: var(--color-text-secondary); line-height: 1.8; }
.snapshot-meta, .pipeline, .strata-panel, .cases-panel, .empty-state { background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); box-shadow: var(--card-shadow); }
.snapshot-meta { display: flex; flex-wrap: wrap; gap: var(--ys-space-4); padding: var(--ys-space-4) var(--ys-space-5); color: var(--color-text-secondary); font-size: var(--ys-font-sm); }
.snapshot-meta__readonly { color: var(--color-success); font-weight: 600; }
.pipeline { padding: var(--ys-space-4) var(--ys-space-5); border-inline-start: 4px solid var(--color-primary); }
.pipeline__label { color: var(--color-text-muted); font-size: var(--ys-font-xs); }.pipeline__value { margin-top: 4px; color: var(--color-text-primary); font-weight: 600; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ys-space-4); }.metric-card { padding: var(--ys-space-5); background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); }.metric-card--hero { border-top: 3px solid var(--color-primary); }.metric-card__label { margin: 0; color: var(--color-text-secondary); font-size: var(--ys-font-sm); }.metric-card__value { margin: var(--ys-space-2) 0; color: var(--color-text-primary); font-size: 2.2rem; font-weight: 700; font-variant-numeric: tabular-nums; }.metric-card__value span { margin-left: 3px; font-size: var(--ys-font-md); }.metric-card__hint, .section-head p, .stratum p, .stratum small { color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.strata-panel, .cases-panel { padding: var(--ys-space-5); }.section-head { margin-bottom: var(--ys-space-4); }.section-head h2 { margin: 0; font-size: var(--ys-font-lg); }.section-head p { margin: 4px 0 0; }.section-head--row { display: flex; align-items: end; justify-content: space-between; gap: var(--ys-space-4); }.strata-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ys-space-4); }.stratum { padding: var(--ys-space-4); background: var(--color-bg-subtle); border-radius: var(--card-radius); }.stratum__head { display: flex; justify-content: space-between; font-weight: 600; }.stratum__bar { height: 8px; margin: var(--ys-space-3) 0; overflow: hidden; background: var(--color-border); border-radius: 999px; }.stratum__bar div { height: 100%; border-radius: inherit; }.stratum p { margin: 0 0 4px; }.cases-count { color: var(--color-text-muted); font-size: var(--ys-font-xs); }.case-list { display: grid; gap: var(--ys-space-2); margin: 0; padding: 0; list-style: none; }.case-row { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: var(--ys-space-3); padding: var(--ys-space-3); border: 1px solid var(--color-border); border-radius: var(--card-radius); }.case-row.is-miss { border-color: var(--color-danger); }.case-row__rank { color: var(--color-text-secondary); font-size: var(--ys-font-xs); font-weight: 600; }.case-row__query { margin: 0 0 6px; color: var(--color-text-primary); font-weight: 600; }.case-row__docs { margin: 3px 0 0; color: var(--color-text-secondary); font-size: var(--ys-font-xs); word-break: break-word; }.empty-state { padding: var(--ys-space-8); text-align: center; }.empty-state h2 { margin: 0 0 var(--ys-space-2); }.empty-state p { margin: 0; color: var(--color-text-secondary); }
.trace-details { margin-top: var(--ys-space-3); border-top: 1px solid var(--color-border); padding-top: var(--ys-space-2); }.trace-details summary { cursor: pointer; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 600; }.trace-details dl { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: var(--ys-space-1) var(--ys-space-3); margin: var(--ys-space-2) 0 0; font-size: var(--ys-font-xs); }.trace-details dt { color: var(--color-text-muted); }.trace-details dd { margin: 0; color: var(--color-text-secondary); overflow-wrap: anywhere; }
@media (max-width: 800px) { .metric-grid, .strata-list { grid-template-columns: 1fr; }.section-head--row { align-items: stretch; flex-direction: column; } }
</style>
