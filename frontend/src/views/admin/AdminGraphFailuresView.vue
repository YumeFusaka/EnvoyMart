<script setup lang="ts">
import { fetchGraphFailures, fetchGraphFailureStats, type GraphFailureRecord, type GraphFailureStats } from '@/api/admin/knowledge'
import ErrorState from '@/components/ui/ErrorState.vue'
import { onMounted, onBeforeUnmount, ref, reactive, computed } from 'vue'
const rows = ref<GraphFailureRecord[]>([])
const stats = ref<GraphFailureStats | null>(null)
const loading = ref(false)
const error = ref('')
const page = ref(1)
const pageSize = 12
const filters = reactive({ batchId: '', docNo: '', stage: '', reasonCode: '', entityKey: '', relation: '' })
const appliedBatch = ref('')
const stageLabels: Record<string, string> = { EXTRACT: '抽取失败', VALIDATION: '事实校验拒绝', RELATION_VALIDATE: '关系校验拒绝', NORMALIZE: '归一化跳过', LINK: '实体链接跳过', CATALOG: '目录跳过', BATCH: '批次跳过', WRITE: '写入失败', UNKNOWN: '未分类阶段' }
const reasonLabels: Record<string, string> = { VOCABULARY: '关系或端点类型不在封闭词表', UNANCHORED: '端点/组合成员没有被正文引文锚定', UNGROUNDED: '引文没有逐字命中正文', MALFORMED: '候选结构或实体形状不合法', SELF_LOOP: '两端归一后是同一实体' }
const stageOptions = computed(() => [...new Set([...Object.keys(stageLabels), ...Object.keys(stats.value?.byStage ?? {})])])
const reasonOptions = computed(() => [...new Set([...Object.keys(reasonLabels), ...Object.keys(stats.value?.byReason ?? {})])])
let generation = 0
async function load() {
  const current = ++generation
  const query = Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim() || undefined]))
  loading.value = true
  error.value = ''
  try {
    const [records, summary] = await Promise.all([fetchGraphFailures({ ...query, limit: 5000 }), fetchGraphFailureStats(query.batchId)])
    if (current !== generation) return
    rows.value = records
    stats.value = summary
    appliedBatch.value = query.batchId ?? ''
    page.value = 1
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '诊断记录加载失败'
  } finally {
    if (current === generation) loading.value = false
  }
}
function clearFilters() {
  Object.assign(filters, { batchId: '', docNo: '', stage: '', reasonCode: '', entityKey: '', relation: '' })
  void load()
}
function sourceLink(row: GraphFailureRecord) {
  return { name: 'knowledge-doc', params: { docNo: row.docNo }, query: {
    ...(row.chunkId ? { chunk: row.chunkId } : {}),
    ...(row.quote ? { quote: row.quote } : {}),
    ...(row.quoteOffsetStart != null ? { start: String(row.quoteOffsetStart) } : {}),
    ...(row.quoteOffsetEnd != null ? { end: String(row.quoteOffsetEnd) } : {}),
  } }
}
onMounted(load)
onBeforeUnmount(() => { generation++ })
const visible = computed(() => rows.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const reasonText = (code: string) => reasonLabels[code] ?? '候选未通过校验'
</script>
<template>
  <div class="admin-page">
    <header class="page-head"><div><p class="eyebrow">Graph Diagnostics</p><h1>图谱构建诊断</h1><p>这里记录的是模型候选被事实校验拒绝或构建阶段跳过的账本，不代表已有图谱事实全部失效。</p></div><button class="toolbar-btn" type="button" :disabled="loading" @click="load">刷新</button></header>
    <section class="explain-grid"><article><b>AI 提候选</b><span>模型只提出实体与关系，候选不是已确认事实。</span></article><article><b>规则判真</b><span>封闭词表、端点锚定与逐字引文校验决定候选是否成立。</span></article><article><b>Neo4j 保存通过事实</b><span>只有通过校验的事实入图；抽取、写入等外部失败可重试，事实拒绝不能靠重试放行。</span></article><article><b>{{ appliedBatch || '全部批次' }}汇总</b><span>记录 {{ stats?.failed ?? '—' }} 条 · 可重试 {{ stats?.retryable ?? '—' }} 条 · 批次 {{ stats?.batches ?? '—' }}</span></article></section>
    <section v-if="stats" class="panel summary" aria-label="批次诊断分布"><h2>批次阶段与原因分布</h2><p>统计覆盖{{ appliedBatch ? `批次 ${appliedBatch}` : '全部批次' }}的全部记录，不随下方文档、实体等筛选改变。</p><dl class="distribution"><template v-for="(count, key) in stats.byStage" :key="key"><dt>{{ stageLabels[key] || key }} <code>{{ key }}</code></dt><dd>{{ count }} 条</dd></template></dl><dl class="distribution"><template v-for="(count, key) in stats.byReason" :key="key"><dt>{{ key }}</dt><dd>{{ count }} 条</dd></template></dl><p v-if="!Object.keys(stats.byStage).length">该批次暂无诊断记录。</p></section>
    <section class="panel">
      <form class="filters" aria-label="诊断筛选" @submit.prevent="load">
        <label>构建批次<el-input v-model="filters.batchId" aria-label="构建批次" clearable placeholder="批次 ID（精确）" /></label>
        <label>文档编号<el-input v-model="filters.docNo" aria-label="文档编号" clearable placeholder="文档编号（精确）" /></label>
        <label>阶段<el-select v-model="filters.stage" aria-label="阶段" clearable placeholder="全部阶段"><el-option v-for="key in stageOptions" :key="key" :label="`${stageLabels[key] || key} · ${key}`" :value="key" /></el-select></label>
        <label>原因码<el-select v-model="filters.reasonCode" aria-label="原因码" clearable placeholder="全部原因码"><el-option v-for="key in reasonOptions" :key="key" :label="key" :value="key" /></el-select></label>
        <label>实体<el-input v-model="filters.entityKey" aria-label="实体" clearable placeholder="候选或规范化端点（精确）" /></label>
        <label>关系<el-input v-model="filters.relation" aria-label="关系" clearable placeholder="关系类型（精确）" /></label>
        <div class="filter-actions"><button class="query-btn" type="submit" :disabled="loading">筛选</button><button class="toolbar-btn" type="button" :disabled="loading" @click="clearFilters">清空筛选</button></div>
      </form>
      <ErrorState v-if="error" :message="error" :on-retry="load" />
      <template v-else>
        <p class="result-note">本次返回 {{ rows.length }} 条；每次最多返回最近 5000 条，达到上限时请按批次或文档缩小范围。</p>
        <el-table v-loading="loading" :data="visible" row-key="id" stripe empty-text="暂无诊断记录">
          <el-table-column type="expand"><template #default="scope">
            <div class="detail-box">
              <b>候选与校验详情</b><p>{{ scope.row.detail }}</p>
              <dl>
                <dt>记录 ID</dt><dd>{{ scope.row.id }}</dd>
                <dt>文档</dt><dd>{{ scope.row.docNo || '未记录' }} <RouterLink v-if="scope.row.docNo" class="source-link" :to="sourceLink(scope.row)">查看原文{{ scope.row.quote ? '并核对引文' : '' }}</RouterLink></dd>
                <dt>候选实体</dt><dd>{{ scope.row.entityKey || '未记录' }}</dd>
                <dt>原始候选</dt><dd class="raw-candidate">{{ scope.row.rawCandidate || '未记录' }}</dd>
                <dt>规范化头端点</dt><dd>{{ scope.row.normalizedHeadKind || '类型未记录' }} · {{ scope.row.normalizedHead || '未记录' }}</dd>
                <dt>规范化尾端点</dt><dd>{{ scope.row.normalizedTailKind || '类型未记录' }} · {{ scope.row.normalizedTail || '未记录' }}</dd>
                <dt>关系</dt><dd>{{ scope.row.relation || '未记录' }}</dd>
                <dt>逐字引文</dt><dd class="candidate-quote">{{ scope.row.quote || '未记录' }}</dd>
                <dt>引文偏移</dt><dd>{{ scope.row.quoteOffsetStart ?? '未记录' }} → {{ scope.row.quoteOffsetEnd ?? '未记录' }}（UTF-16，结束位置不含）</dd>
                <dt>别名命中</dt><dd>{{ scope.row.aliasHit == null ? '未记录' : scope.row.aliasHit ? '是' : '否' }}</dd>
                <dt>切片</dt><dd>{{ scope.row.chunkId || '未记录' }}</dd>
                <dt>构建批次</dt><dd>{{ scope.row.batchId }}</dd>
                <dt>阶段 / 原因码</dt><dd>{{ scope.row.stage }} / {{ scope.row.reasonCode }}</dd>
                <dt>发生时间</dt><dd>{{ scope.row.occurredAt }}</dd>
                <dt>重试</dt><dd>{{ scope.row.retryable ? '可重试' : '不可重试' }}</dd>
              </dl>
            </div>
          </template></el-table-column>
          <el-table-column prop="occurredAt" label="时间" width="170" />
          <el-table-column prop="batchId" label="批次" width="145" show-overflow-tooltip />
          <el-table-column prop="docNo" label="文档" width="100" />
          <el-table-column prop="stage" label="阶段" width="150" />
          <el-table-column label="判定" width="190"><template #default="scope"><strong>{{ scope.row.reasonCode }}</strong><small>{{ reasonText(scope.row.reasonCode) }}</small></template></el-table-column>
          <el-table-column prop="entityKey" label="候选实体" width="160" />
          <el-table-column prop="relation" label="关系" width="140" />
          <el-table-column prop="detail" label="具体原因" min-width="260" show-overflow-tooltip />
          <el-table-column label="重试" width="95"><template #default="scope">{{ scope.row.retryable ? '可重试' : '不可重试' }}</template></el-table-column>
        </el-table>
        <el-pagination v-if="rows.length > pageSize" v-model:current-page="page" class="pager" layout="total, prev, pager, next" :page-size="pageSize" :total="rows.length" />
      </template>
    </section>
  </div>
</template>
<style scoped>
.admin-page { display: grid; gap: var(--ys-space-5); min-width: 0; }
.page-head { display: flex; justify-content: space-between; gap: var(--ys-space-4); align-items: end; }
.eyebrow { margin: 0; color: var(--color-primary-strong); font-size: var(--ys-font-xs); font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.page-head h1 { margin: var(--ys-space-1) 0; font-size: var(--ys-font-2xl); }
.page-head p, .summary p, .result-note { margin: 0; color: var(--color-text-secondary); }
.panel, .explain-grid article { min-width: 0; background: var(--color-bg-surface); border: var(--card-border); border-radius: var(--card-radius); padding: var(--ys-space-4); box-shadow: var(--card-shadow); }
.toolbar-btn, .query-btn { min-height: 2.375rem; padding: 0 var(--ys-space-4); border: 1px solid var(--color-border); border-radius: var(--ys-radius-sm); background: var(--color-bg-surface); cursor: pointer; }
.query-btn { background: var(--color-primary); color: var(--color-text-inverse); border: 0; }
.toolbar-btn:hover { border-color: var(--color-primary); color: var(--color-primary-strong); }
button:disabled { cursor: wait; opacity: .6; }
button:focus-visible, .source-link:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.explain-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ys-space-3); }
.explain-grid article, .filters label { display: grid; gap: var(--ys-space-1); }
.explain-grid span, small { display: block; color: var(--color-text-muted); font-size: var(--ys-font-xs); }
.summary { display: grid; gap: var(--ys-space-2); }
.summary h2 { margin: 0; font-size: var(--ys-font-base); }
.distribution { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--ys-space-1) var(--ys-space-3); margin: 0; max-width: 42rem; font-size: var(--ys-font-sm); }
.distribution dt { color: var(--color-text-secondary); }
.distribution dd { margin: 0; font-variant-numeric: tabular-nums; }
.filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ys-space-3); margin-bottom: var(--ys-space-4); }
.filters label { color: var(--color-text-secondary); font-size: var(--ys-font-sm); }
.filter-actions { display: flex; flex-wrap: wrap; gap: var(--ys-space-2); }
.result-note { margin-bottom: var(--ys-space-3); font-size: var(--ys-font-xs); }
.detail-box { display: grid; gap: var(--ys-space-1); padding: var(--ys-space-3); background: var(--color-bg-subtle); border-inline-start: 3px solid var(--color-primary); }
.detail-box p { margin: 0; color: var(--color-text-secondary); }
.detail-box dl { display: grid; grid-template-columns: 7rem minmax(0, 1fr); gap: var(--ys-space-2) var(--ys-space-3); margin: var(--ys-space-2) 0; }
.detail-box dt { color: var(--color-text-muted); }
.detail-box dd { margin: 0; color: var(--color-text-secondary); font-size: var(--ys-font-sm); overflow-wrap: anywhere; }
.raw-candidate, .candidate-quote { white-space: pre-wrap; }
.source-link { margin-inline-start: var(--ys-space-3); color: var(--color-primary-strong); }
.pager { justify-content: flex-end; margin-top: var(--ys-space-4); }
@media (max-width: 960px) { .explain-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .explain-grid, .filters { grid-template-columns: minmax(0, 1fr); } }
</style>
