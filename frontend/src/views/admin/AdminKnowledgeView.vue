<script setup lang="ts">
/**
 * 知识库管理。
 *
 * 这一页补的是「上传全新文档 → 自动切分 → 进向量库 → 图谱更新」的前端入口。
 * 此前知识库只有只读接口，新增内容必须改仓库里的 md 再重启 —— 运营碰不到。
 *
 * 三件事按顺序发生，界面上也必须按顺序表达：
 *
 *   1. **保存文档**：正文落进 knowledge_document 并切好切片。这一步只动事实源。
 *   2. **重建索引**：向量库与知识图谱从事实源重新派生。这一步是异步的，
 *      实测七十几秒，期间检索是空的（先清空再写入）。
 *   3. **重建完成**：新文档此刻才真的能被搜到、被引用。
 *
 * 把 1 和 2 分成两个动作而不是「保存即重建」：重建要拉全量语料并调模型抽图谱，
 * 是个分钟级动作。合成一个按钮的话，保存失败与重建失败在用户看来是同一件事，
 * 而他需要知道的恰恰是「文档存上了没有」—— 存上了但没重建，重跑一次重建就行；
 * 没存上，得回头改正文。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listSpus } from '@/api/admin/product'
import {
  changeDocumentStatus,
  fetchKnowledgeCoverage,
  fetchReindexStatus,
  getDocumentAdmin,
  getDocumentBindings,
  listDocumentsAdmin,
  listGraphBuildFailures,
  reindexDocument,
  reindexKnowledge,
  upsertDocument,
} from '@/api/admin/knowledge'
import { useAdminList, useResponsiveColumns } from '@/composables/useAdminList'
import { formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { DocumentSummary } from '@/types/models'

type KnowledgeQuery = { scope?: string; keyword?: string; status?: number; page: number; size: number }

const { query, records, loading, error, search, resetFilters, load } = useAdminList<
  DocumentSummary,
  KnowledgeQuery
>(
  (params) => listDocumentsAdmin(params),
  { scope: undefined, keyword: '', status: undefined, page: 0, size: 50 },
  { immediate: false },
)

onMounted(() => {
  void load(0)
  void loadCoverage()
})

/** 表格容器。列宽由它实测的宽度反推，所以宽度变化源是它而不是 window */
const tableRef = ref<HTMLElement | null>(null)

/**
 * 各列的声明宽度与最小宽度，顺序与模板一致。
 * 声明值之和 950 在 1600 宽下装得下（表宽 1288），但 1040 宽下表只有 728——
 * 不收缩的话 Element Plus 会把「文档」列砍到极限，标题变成一列一个字。
 */
const { widths: colW } = useResponsiveColumns(
  [
    { width: 220, min: 220 },
    { width: 200, min: 170 },
    { width: 80, min: 70 },
    { width: 90, min: 80 },
    { width: 80, min: 70 },
    { width: 150, min: 140 },
    { width: 130, min: 110 },
  ],
  tableRef,
)

/** 正在做单篇增量的文档编号；同时给列表与保存按钮一个加载态 */
const syncing = ref<string | null>(null)
/** 知识库改过但索引还没跟上。保存/停用后由 syncOne 负责把它清掉 */
const staleIndex = ref(false)

/**
 * 让刚改动的那一篇立刻进索引。
 * <p>
 * 只重建这一篇，不走全量：全量要重编码整库、重抽全量图谱（七十几秒、十几次模型调用），
 * 而这里通常只改了一篇。代价差两个数量级，而效果对「这一篇能不能被搜到」是一样的。
 * <p>
 * 失败<b>不当作保存失败</b>：文档已经落库了，回滚它才是错的。这里只提示「索引还没跟上」，
 * 并把全量重建的入口留给人工——索引是派生物，它可以落后，但不该让事实源回滚。
 */
async function syncOne(docNo: string) {
  syncing.value = docNo
  try {
    const result = await reindexDocument(docNo)
    staleIndex.value = false
    if (result.graphUpdated) {
      ElMessage.success(`${docNo} 已保存，向量库与图谱均已更新`)
    } else {
      // 图谱没更新成功不算失败：检索侧已经生效，图谱保持上一版
      ElMessage.warning(`${docNo} 已保存，检索已生效；图谱未更新（可稍后点「重建索引」）`)
    }
  } catch {
    staleIndex.value = true
    ElMessage.warning(`${docNo} 已保存，但索引更新失败；请点「重建索引」重试`)
  } finally {
    syncing.value = null
  }
}

/**
 * 领域与来源取值与后端 SCOPES / SOURCES 一一对应。
 * 写死在这里而不是从接口拉：这是一份契约清单，拉不到时页面会变成空下拉，
 * 用户会以为「没有可选项」而不是「接口挂了」。
 */
const SCOPE_OPTIONS = [
  { value: 'nutrition', label: '营养健康' },
  { value: 'after_sale', label: '售后' },
  { value: 'logistics', label: '物流' },
  { value: 'payment', label: '支付' },
  { value: 'promotion', label: '促销' },
  { value: 'food_safety', label: '食品安全' },
]
const SOURCE_OPTIONS = [
  { value: 'manual', label: '产品说明书' },
  { value: 'policy', label: '平台政策' },
  { value: 'regulation', label: '监管规范' },
  { value: 'spec', label: '技术规格' },
  { value: 'guide', label: '使用指南' },
]

function scopeLabel(scope: string) {
  return SCOPE_OPTIONS.find((s) => s.value === scope)?.label ?? scope
}

function sourceLabel(source: string) {
  return SOURCE_OPTIONS.find((s) => s.value === source)?.label ?? source
}

// ==================== 编辑 ====================

const editing = ref(false)
const saving = ref(false)
const form = ref({
  docNo: '',
  title: '',
  source: 'manual',
  scope: 'nutrition',
  version: 'v1',
  tags: '',
  content: '',
  subjectSpuIds: [] as number[],
})
const productOptions = ref<{ id: number; name: string; spuCode: string; status: number }[]>([])
const productOptionsLoading = ref(false)

async function loadProductOptions() {
  productOptionsLoading.value = true
  try {
    const page = await listSpus({ status: 1, page: 0, size: 500, sort: 'updated_desc' })
    productOptions.value = page.records
  } catch {
    productOptions.value = []
  } finally {
    productOptionsLoading.value = false
  }
}

function openCreate() {
  form.value = {
    docNo: '',
    title: '',
    source: 'manual',
    scope: 'nutrition',
    version: 'v1',
    tags: '',
    content: '',
    subjectSpuIds: [],
  }
  void loadProductOptions()
  editing.value = true
}

function openEdit(row: DocumentSummary) {
  // 列表不带正文（那是详情接口的事），先占位再异步拉全文
  form.value = {
    docNo: row.docNo,
    title: row.title,
    source: row.source,
    scope: row.scope,
    version: row.version,
    tags: row.tags ?? '',
    content: '',
    subjectSpuIds: [],
  }
  editing.value = true
  void Promise.all([loadContent(row.docNo), loadBindings(row.docNo), loadProductOptions()])
}

async function loadBindings(docNo: string) {
  try {
    const ids = await getDocumentBindings(docNo)
    if (form.value.docNo === docNo) {
      form.value.subjectSpuIds = ids
    }
  } catch {
    form.value.subjectSpuIds = []
  }
}

async function loadContent(docNo: string) {
  try {
    const detail = await getDocumentAdmin(docNo)
    // 只在用户没换过文档、也还没开始输入正文时回填 —— 否则会在打字途中覆盖掉
    if (form.value.docNo === docNo && !form.value.content) {
      form.value.content = detail.content ?? ''
    }
  } catch {
    // 拦截器已提示；正文留空，保存时会被后端的非空校验挡住
  }
}

async function save() {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写正文 —— 切分与图谱抽取都以它为准')
    return
  }
  if (form.value.source === 'manual' && form.value.subjectSpuIds.length === 0) {
    ElMessage.warning('产品说明书必须绑定至少一个商品')
    return
  }
  saving.value = true
  try {
    const docNo = await upsertDocument({
      docNo: form.value.docNo || undefined,
      title: form.value.title.trim(),
      source: form.value.source,
      scope: form.value.scope,
      version: form.value.version.trim() || 'v1',
      tags: form.value.tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
      content: form.value.content,
      subjectSpuIds: form.value.source === 'manual' ? form.value.subjectSpuIds : [],
    })
    await syncOne(docNo)
    editing.value = false
    await load()
  } catch {
    // 拦截器已提示；抽屉保持打开，用户改完可以重试
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: DocumentSummary) {
  const next = row.status === 1 ? 0 : 1
  try {
    await changeDocumentStatus(row.docNo, next)
    await syncOne(row.docNo)
    await load()
  } catch {
    // 拦截器已提示
  }
}

// ==================== 索引重建 ====================

const reindexing = ref(false)
const indexStatus = ref<Awaited<ReturnType<typeof fetchReindexStatus>> | null>(null)
let pollTimer: number | undefined

/**
 * 重建期间的检索是空的，这一条必须显示出来 —— 否则运维会看到
 * 「AI 怎么突然什么都不知道了」，而系统的实际状态是「正在重建」。
 */
const indexHint = computed(() => {
  if (reindexing.value) {
    return '索引重建中：这段时间 AI 的回答会偏「知识库中没有相关依据」，属于预期行为'
  }
  if (staleIndex.value) {
    return '知识库已改动，索引还是旧的 —— 重建之后才会生效'
  }
  return ''
})

async function refreshStatus() {
  try {
    const status = await fetchReindexStatus()
    indexStatus.value = status
    reindexing.value = status.running
    if (status.running) {
      schedulePoll()
    }
  } catch {
    // 状态拉不到不影响主流程
  }
}

function stopPoll() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function schedulePoll() {
  if (pollTimer) {
    return
  }
  pollTimer = window.setInterval(async () => {
    try {
      const status = await fetchReindexStatus()
      indexStatus.value = status
      if (!status.running) {
        reindexing.value = false
        staleIndex.value = false
        stopPoll()
        if (status.error) {
          ElMessage.error(`索引重建失败：${status.error}`)
        } else {
          const result = status.result
          ElMessage.success(
            `索引重建完成：文档 ${result?.documentCount ?? '?'} 篇 / 切片 ${result?.chunkCount ?? '?'} 片`,
          )
        }
      }
    } catch {
      stopPoll()
      reindexing.value = false
    }
  }, 3000)
}

onUnmounted(stopPoll)

async function reindex() {
  reindexing.value = true
  try {
    await reindexKnowledge()
    await refreshStatus()
  } catch {
    reindexing.value = false
  }
}

async function onReset() {
  await resetFilters()
}
// ==================== 商品资料覆盖率 ====================

/**
 * 覆盖率读数：在售商品里有多少是有说明书支撑的。
 *
 * 它回答的是「存储质量的欠账」——未覆盖的商品，用户问到时系统只能答
 * 「知识库里没有」。所以这一块的落点是**可行动**：每个未覆盖的商品点一下就进它的编辑页。
 *
 * `available` 与计数分开：图谱或商品目录不可用时三个计数都是 0，
 * 与「全部覆盖」在界面上长得一模一样。不加这个标志，一次故障会显示成「一切正常」。
 */
const coverage = ref<Awaited<ReturnType<typeof fetchKnowledgeCoverage>> | null>(null)
const coverageLoading = ref(false)

async function loadCoverage() {
  coverageLoading.value = true
  try {
    coverage.value = await fetchKnowledgeCoverage()
  } catch {
    // 拦截器已提示；把读数置空，让区块显示成「没查成」而不是「全绿」
    coverage.value = null
  } finally {
    coverageLoading.value = false
  }
}

/** 未覆盖原因的文案。两种原因的修法不同，不能合起来说 */
function uncoveredReason(reason: 'NO_NODE' | 'NO_DOCUMENT') {
  return reason === 'NO_NODE'
    ? '图谱里还没有这个商品节点'
    : '图谱里有节点，但没有文档支持它'
}

/** 覆盖率的百分比读数。分母为 0 时给 null —— 那是「还没有商品」而不是「一个都没覆盖」 */
const coveragePercent = computed(() => {
  const data = coverage.value
  if (!data || data.totalSpu === 0) {
    return null
  }
  return Math.round((data.coveredSpu / data.totalSpu) * 100)
})

const graphFailures = ref<Awaited<ReturnType<typeof listGraphBuildFailures>>>([])
const graphFailureLoading = ref(false)
const graphFailureError = ref(false)
const graphFailureFilters = ref({ batchId: '', docNo: '', stage: '', reasonCode: '' })
async function loadGraphFailures() {
  graphFailureLoading.value = true
  graphFailureError.value = false
  try {
    graphFailures.value = await listGraphBuildFailures({ ...graphFailureFilters.value, limit: 100 })
  } catch {
    graphFailureError.value = true
  } finally {
    graphFailureLoading.value = false
  }
}
onMounted(() => void loadGraphFailures())
</script>

<template>
  <section class="kb">
    <div class="admin-panel kb-head">
      <div class="admin-toolbar">
        <h2 class="admin-toolbar__title">知识库</h2>
        <span class="admin-toolbar__count">
          上传或编辑后自动切分并同步进向量库与知识图谱，AI 立刻引用得到它
        </span>
        <div class="admin-toolbar__actions">
          <el-button
            :icon="Refresh"
            :loading="reindexing"
            title="整库重抽：上传/编辑后的单篇已自动生效，这里只用于兜底或全量修复"
            @click="reindex"
            >重建索引</el-button
          >
          <el-button type="primary" @click="openCreate">上传文档</el-button>
        </div>
      </div>

      <el-alert
        v-if="indexHint"
        class="admin-index-hint"
        :title="indexHint"
        :type="reindexing ? 'warning' : 'info'"
        :closable="false"
        show-icon
      />

      <div v-if="indexStatus && !indexStatus.running && !staleIndex" class="admin-index-meta">
        <span v-if="indexStatus.finishedAt">
          上次重建：{{ formatDateTime(indexStatus.finishedAt) }}
        </span>
        <span v-if="indexStatus.result">
          文档 {{ indexStatus.result.documentCount ?? '—' }} 篇 ·
          切片 {{ indexStatus.result.chunkCount ?? '—' }} 片
        </span>
      </div>
    </div>
    <!-- 商品资料覆盖率：这是「多少商品答得上来」的读数，不是文档列表的附属统计。
         它排在文档列表之前，因为缺资料的商品才是要去处理的那批。 -->
    <section class="coverage" aria-label="商品资料覆盖率">
      <div class="coverage__head">
        <h2 class="coverage__title">商品资料覆盖率</h2>
        <el-button
          link
          :icon="Refresh"
          :loading="coverageLoading"
          @click="loadCoverage"
        >
          刷新
        </el-button>
      </div>

      <p v-if="coverageLoading && !coverage" class="coverage__hint">正在统计…</p>

      <p v-else-if="!coverage" class="coverage__hint coverage__hint--bad">
        覆盖率没查成（商品服务或图谱服务不可用）。这不代表商品都覆盖了，请稍后重试。
      </p>

      <p v-else-if="!coverage.available" class="coverage__hint coverage__hint--bad">
        覆盖率没查成：{{ coverage.reason || '图谱或商品目录暂时不可用' }}。
        <strong>这不代表商品都覆盖了</strong>，请稍后重试。
      </p>

      <template v-else>
        <div class="coverage__stats">
          <div class="coverage__stat">
            <span class="coverage__value">{{ coverage.coveredSpu }} / {{ coverage.totalSpu }}</span>
            <span class="coverage__label">在售商品有说明书支撑</span>
          </div>
          <div class="coverage__stat">
            <span class="coverage__value" :class="{ 'coverage__value--warn': coverage.uncoveredSpu.length > 0 }">
              {{ coverage.uncoveredSpu.length }}
            </span>
            <span class="coverage__label">未覆盖，需要补资料</span>
          </div>
          <div class="coverage__stat">
            <span class="coverage__value">{{ coveragePercent === null ? '—' : coveragePercent + '%' }}</span>
            <span class="coverage__label">覆盖率</span>
          </div>
        </div>

        <p v-if="coverage.uncoveredSpu.length === 0" class="coverage__hint coverage__hint--good">
          全部在售商品都有说明书支撑。新上架商品如果这里没出现，是因为图谱还没重建 ——
          上传说明书后会自动同步。
        </p>

        <div v-else class="coverage__list">
          <p class="coverage__list-hint">
            这些商品在图谱里还没有说明书支撑，用户问到时会答「知识库里没有」。
            点商品名进它的编辑页，「说明书与知识依据」一栏会告诉你接上了没有。
          </p>
          <ul class="coverage__items">
            <li v-for="item in coverage.uncoveredSpu" :key="item.spuKey" class="coverage__item">
              <RouterLink
                class="coverage__item-name"
                :to="`/admin/products/${item.spuKey.replace(/^SPU/i, '')}/edit`"
              >
                {{ item.name }}
              </RouterLink>
              <span class="coverage__item-key">{{ item.spuKey }}</span>
              <el-tag size="small" effect="plain" type="warning">{{ uncoveredReason(item.reason) }}</el-tag>
            </li>
          </ul>
        </div>
      </template>
    </section>

    <div class="admin-panel">
      <div class="admin-filters">
        <div class="admin-filters__item">
          <label class="admin-filters__label" for="kb-keyword">标题 / 标签</label>
          <el-input
            id="kb-keyword"
            v-model="query.keyword"
            placeholder="标题或标签关键词"
            clearable
            @keyup.enter="search"
          />
        </div>

        <div class="admin-filters__item">
          <label class="admin-filters__label" for="kb-scope">领域</label>
          <el-select id="kb-scope" v-model="query.scope" clearable placeholder="全部领域">
            <el-option v-for="s in SCOPE_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </div>

        <div class="admin-filters__item">
          <label class="admin-filters__label" for="kb-status">状态</label>
          <el-select id="kb-status" v-model="query.status" clearable placeholder="全部状态">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </div>

        <div class="admin-filters__actions">
          <el-button type="primary" :icon="Search" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="onReset">重置</el-button>
        </div>
      </div>
      <div class="admin-toolbar">
        <h2 class="admin-toolbar__title">文档</h2>
        <span class="admin-toolbar__count">共 {{ records.length }} 篇</span>
      </div>

      <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

      <template v-else>
        <div ref="tableRef" class="admin-table">
          <!-- 列宽由 useResponsiveColumns 按容器实测宽度算：声明值之和装不下时收缩，
               否则 Element Plus 会把最宽的列砍到极限（标题一列一个字），
               而带 fixed 列的表格还会让最右列被盖住且滚不到。 -->
          <el-table v-loading="loading" :data="records" style="width: 100%">
            <el-table-column label="文档" :width="colW[0]">
              <template #default="{ row }">
                <div class="admin-stack">
                  <span class="admin-cell--strong">{{ row.title }}</span>
                  <span class="admin-cell--tiny">{{ row.docNo }} · {{ row.version }}</span>
                </div>
              </template>
          </el-table-column>

          <el-table-column label="来源 / 领域" :width="colW[1]">
            <template #default="{ row }">
              <!-- 来源与领域并排而不堆叠：flex 列容器的子项默认会被拉成整列宽，
                   标签因此看着像个可编辑输入框 -->
              <div class="admin-inline">
                <span class="admin-cell--tiny">{{ sourceLabel(row.source) }}</span>
                <el-tag size="small" effect="plain" type="info">{{ scopeLabel(row.scope) }}</el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="切片" :width="colW[2]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ row.chunkCount }} 片</span>
            </template>
          </el-table-column>

          <el-table-column label="正文" :width="colW[3]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ row.contentLength }} 字</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" :width="colW[4]">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="更新时间" :width="colW[5]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" :width="colW[6]" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="syncing === row.docNo" @click="openEdit(row)"
                >编辑</el-button
              >
              <el-button
                link
                type="primary"
                :loading="syncing === row.docNo"
                :disabled="syncing === row.docNo"
                @click="toggleStatus(row)"
              >
                {{ row.status === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">还没有知识文档，点右上角「上传文档」添加一篇</p>
          </template>
        </el-table>
      </div>
      </template>
    </div>

    <section class="admin-panel graph-failure-panel" aria-label="图谱构建失败记录">
      <div class="coverage__head">
        <div>
          <h2 class="coverage__title">图谱构建记录</h2>
          <p class="coverage__list-hint">按批次、文档、阶段和原因码查看逐条失败/跳过记录。</p>
        </div>
        <el-button :icon="Refresh" :loading="graphFailureLoading" @click="loadGraphFailures">刷新</el-button>
      </div>
      <div class="graph-failure-filters">
        <el-input v-model="graphFailureFilters.batchId" clearable placeholder="批次号" />
        <el-input v-model="graphFailureFilters.docNo" clearable placeholder="文档号" />
        <el-select v-model="graphFailureFilters.stage" clearable placeholder="阶段">
          <el-option label="抽取" value="EXTRACT" /><el-option label="校验" value="VALIDATION" />
          <el-option label="写入" value="WRITE" /><el-option label="批次" value="BATCH" />
        </el-select>
        <el-input v-model="graphFailureFilters.reasonCode" clearable placeholder="原因码" />
        <el-button type="primary" @click="loadGraphFailures">查询</el-button>
      </div>
      <el-table v-loading="graphFailureLoading" :data="graphFailures" size="small" empty-text="暂无失败或跳过记录">
        <el-table-column prop="occurredAt" label="时间" width="170" />
        <el-table-column prop="batchId" label="批次" min-width="180" />
        <el-table-column prop="docNo" label="文档" width="110" />
        <el-table-column prop="stage" label="阶段" width="90" />
        <el-table-column prop="reasonCode" label="原因码" width="150" />
        <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip />
        <el-table-column label="可重试" width="80"><template #default="scope">{{ scope.row.retryable ? '是' : '否' }}</template></el-table-column>
        <el-table-column type="expand"><template #default="scope"><dl class="graph-failure-detail"><dt>批次号</dt><dd>{{ scope.row.batchId }}</dd><dt>文档号</dt><dd>{{ scope.row.docNo || '未关联文档' }}</dd><dt>实体键</dt><dd>{{ scope.row.entityKey || '未定位实体' }}</dd><dt>阶段 / 原因码</dt><dd>{{ scope.row.stage }} / {{ scope.row.reasonCode }}</dd><dt>发生时间</dt><dd>{{ scope.row.occurredAt }}</dd><dt>脱敏详情</dt><dd>{{ scope.row.detail || '无补充详情' }}</dd></dl></template></el-table-column>
      </el-table>
      <el-alert v-if="graphFailureError" type="error" title="图谱构建记录读取失败" description="当前结果不是空列表。请检查知识服务后重试。" :closable="false" show-icon />
    </section>

    <el-drawer
      v-model="editing"
      :title="form.docNo ? `编辑文档 ${form.docNo}` : '上传文档'"
      size="640px"
    >
      <el-form label-position="top" class="kb-form">
        <el-form-item label="标题">
          <el-input v-model="form.title" placeholder="如：维生素 D3 软胶囊产品说明书" />
        </el-form-item>

        <div class="kb-form__row">
          <el-form-item label="来源">
            <el-select
              v-model="form.source"
              @change="form.source !== 'manual' && (form.subjectSpuIds = [])"
            >
              <el-option
                v-for="s in SOURCE_OPTIONS"
                :key="s.value"
                :label="s.label"
                :value="s.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="领域">
            <el-select v-model="form.scope">
              <el-option
                v-for="s in SCOPE_OPTIONS"
                :key="s.value"
                :label="s.label"
                :value="s.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="版本">
            <el-input v-model="form.version" placeholder="v2026.03" />
          </el-form-item>
        </div>

        <el-form-item v-if="form.source === 'manual'" label="绑定商品" required>
          <el-select
            v-model="form.subjectSpuIds"
            class="kb-form__product-select"
            multiple
            filterable
            clearable
            :loading="productOptionsLoading"
            placeholder="选择这份说明书对应的商品，可多选"
          >
            <el-option
              v-for="product in productOptions"
              :key="product.id"
              :label="`${product.name}（${product.spuCode}）`"
              :value="product.id"
            />
          </el-select>
          <p class="kb-form__hint kb-form__hint--required">
            商品说明书必须绑定商品。系统按这个绑定维护商品上下架、向量库和知识图谱的一致性，不根据标题猜商品。
          </p>
        </el-form-item>
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="领域文档不绑定商品"
          description="平台政策、监管规范等建立在业务领域上，不属于某个商品，可以留空。"
        />

        <el-form-item label="标签（逗号分隔）">
          <el-input v-model="form.tags" placeholder="维生素D3,钙,用量" />
        </el-form-item>

        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="16"
            placeholder="粘贴文档全文。章节标题请用 Markdown 标题（## 第二章），切分会按层级下钻"
          />
          <p class="kb-form__hint">
            切分按章节层级进行，正文里的 ## / ### 会成为引用回跳的位置路径
          </p>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
/* 知识库是唯一「多块并列」的管理页：覆盖率与文档列表各自一张卡。
   其余九页是一张卡装下全部，所以这里不套 admin-panel 的单一外壳 */
.kb {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-5);
}

/* 页头卡：toolbar 之后紧跟状态提示，底部要留白（其余页只有 toolbar，交给下方表格自带内边距） */
.kb-head {
  padding-bottom: var(--ys-space-5);
}

.admin-index-hint {
  margin-bottom: var(--ys-space-4);
}

.admin-index-meta {
  display: flex;
  gap: var(--ys-space-4);
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-xs);
  color: var(--color-text-muted);
}

.kb-form__row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: var(--ys-space-3);
}

.kb-form__product-select {
  width: 100%;
}

.kb-form__hint--required {
  color: var(--color-warning);
}

.kb-form__hint {
  margin-top: var(--ys-space-2);
  font-size: var(--ys-font-xs);
  color: var(--color-text-muted);
  line-height: 1.6;
}

@media (max-width: 720px) {
  .kb-form__row {
    grid-template-columns: 1fr;
  }
}
/* ==================== 商品资料覆盖率 ==================== */
.coverage {
  padding: var(--ys-space-4) var(--ys-space-5);
  margin-bottom: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  box-shadow: var(--card-shadow);
}

.coverage__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.coverage__title {
  margin: 0;
  font-size: var(--ys-font-md);
  font-weight: 600;
  color: var(--color-text-primary);
}

.coverage__hint {
  margin: var(--ys-space-3) 0 0;
  font-size: var(--ys-font-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.coverage__hint--good {
  color: var(--color-success-strong);
}

.coverage__hint--bad {
  color: var(--color-warning-strong);
}

.coverage__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--ys-space-4);
  margin-top: var(--ys-space-4);
}

.coverage__stat {
  display: grid;
  gap: var(--ys-space-1);
}

.coverage__value {
  font-size: var(--ys-font-xl);
  font-weight: 700;
  color: var(--color-text-primary);
  font-variant-numeric: tabular-nums;
}

/* 未覆盖数 > 0 时才上警示色：0 也标红会让人以为一直在出问题 */
.coverage__value--warn {
  color: var(--color-warning-strong);
}

.coverage__label {
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
}

.coverage__list {
  margin-top: var(--ys-space-4);
}

.coverage__list-hint {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-font-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.coverage__items {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--ys-space-2);
  max-height: 260px;
  overflow-y: auto;
}

.coverage__item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  flex-wrap: wrap;
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
}

.coverage__item-name {
  font-weight: 600;
  color: var(--color-primary);
  text-decoration: none;
}

.coverage__item-name:hover {
  text-decoration: underline;
}

.coverage__item-key {
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}

.graph-failure-detail { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: var(--ys-space-2) var(--ys-space-4); margin: 0; padding: var(--ys-space-3); background: var(--color-bg-surface-muted); overflow-wrap: anywhere; }
.graph-failure-detail dt { color: var(--color-text-muted); }
.graph-failure-detail dd { margin: 0; color: var(--color-text-primary); }
</style>
