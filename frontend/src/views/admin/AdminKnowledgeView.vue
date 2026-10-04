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
import {
  changeDocumentStatus,
  fetchReindexStatus,
  getDocumentAdmin,
  listDocumentsAdmin,
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

onMounted(() => load(0))

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
})

function openCreate() {
  form.value = {
    docNo: '',
    title: '',
    source: 'manual',
    scope: 'nutrition',
    version: 'v1',
    tags: '',
    content: '',
  }
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
  }
  editing.value = true
  void loadContent(row.docNo)
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
</script>

<template>
  <section class="admin-page">
    <header class="admin-head">
      <div>
        <h1 class="admin-head__title">知识库</h1>
        <p class="admin-head__sub">
          上传或编辑后自动切分并同步进向量库与知识图谱，AI 立刻引用得到它；
          「重建索引」是兜底入口，只有在需要整库重抽时才点（会花几十秒、并按整库计费）
        </p>
      </div>
      <div class="admin-head__actions">
        <el-button
          :icon="Refresh"
          :loading="reindexing"
          title="整库重抽：上传/编辑后的单篇已自动生效，这里只用于兜底或全量修复"
          @click="reindex"
          >重建索引</el-button
        >
        <el-button type="primary" @click="openCreate">上传文档</el-button>
      </div>
    </header>

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

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="kb-scope">领域</label>
        <el-select id="kb-scope" v-model="query.scope" clearable placeholder="全部领域">
          <el-option v-for="s in SCOPE_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
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
            <el-select v-model="form.source">
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
.admin-index-hint {
  margin-bottom: var(--ys-space-4);
}

.admin-index-meta {
  display: flex;
  gap: var(--ys-space-4);
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-size-xs);
  color: var(--ys-color-text-tertiary);
}

.kb-form__row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: var(--ys-space-3);
}

.kb-form__hint {
  margin-top: var(--ys-space-2);
  font-size: var(--ys-font-size-xs);
  color: var(--ys-color-text-tertiary);
  line-height: 1.6;
}

@media (max-width: 720px) {
  .kb-form__row {
    grid-template-columns: 1fr;
  }
}
</style>