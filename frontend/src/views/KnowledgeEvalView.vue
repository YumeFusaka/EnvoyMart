<script setup lang="ts">
import { getEvalReport, rerunEval } from '@/api/eval'
import type { EvalCase, EvalRun } from '@/api/eval'
import ErrorState from '@/components/ui/ErrorState.vue'
import { useUserStore } from '@/stores'
import { formatDateTime } from '@/utils/format'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

/**
 * 检索评测报告 —— 「AI 凭什么说检索靠谱」的现场证据。
 *
 * 页面的叙事顺序是刻意的：
 *   1. 先给整数字与随机基线对照（63.3% vs 3.6%，说明不是碰巧）
 *   2. 再拆三档难度（字面 / 口语 / 语义鸿沟，看分数掉在哪一档）
 *   3. 然后把 120 条逐条摆出来，**失败样本不做任何隐藏**
 *   4. 最后是「读这些数字的前提」—— 边界条件主动讲，而不是等被追问
 *
 * 最后一段是这页存在的理由：一个只展示好数字的评测页，和一个把
 * 「这批样本是回归防线、不是质量结论」写在明面上的评测页，
 * 可信度不在一个量级。
 */

const userStore = useUserStore()
const isAdmin = computed(() => userStore.profile?.roleName === 'ADMIN')

const report = ref<EvalRun | null>(null)
const loading = ref(true)
const failed = ref(false)
const rerunning = ref(false)

/** 明细筛选：'' = 全部；'MISS' = 仅未命中；其余为档位 key */
const caseFilter = ref('')

async function load() {
  loading.value = true
  failed.value = false
  try {
    report.value = await getEvalReport()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 现场重跑。文案强调「与 CI 同源」不是修辞：重跑与 CI 门禁读同一份夹具
 * （retrieval-fixtures.json）、用同一套检索器构造，数字本就该逐位一致 ——
 * 这正是这个按钮敢放在页面上的原因。
 */
async function rerun() {
  rerunning.value = true
  try {
    report.value = await rerunEval()
    ElMessage.success('重跑完成 —— 与 CI 门禁同源，数字应当逐位一致')
  } catch {
    // 拦截器已弹提示；这里只负责恢复按钮
  } finally {
    rerunning.value = false
  }
}

onMounted(load)

/** 三档的展示色与一句话说明。颜色从蓝绿到红，对应「难度上去了」这一个叙事 */
const STRATUM_META: Record<string, { color: string; desc: string }> = {
  LEXICAL: { color: 'var(--color-accent)', desc: '查询与文档用词高度一致，关键词检索的舒适区' },
  PARAPHRASE: { color: 'var(--color-warning)', desc: '口语化改写，与文档几乎无字面重合' },
  HARD: { color: 'var(--color-danger)', desc: '词汇与语义都远，关键词路的天然短板' },
}

const TRIGGER_LABEL: Record<string, string> = {
  STARTUP: '服务启动时自动生成',
  MANUAL: '手动重跑',
}

const pct = (value: number) => (value * 100).toFixed(1)
const fixed3 = (value: number) => value.toFixed(3)
/** 实测相对随机基线的倍数 —— 「运气好」到此为止 */
const times = (value: number, baseline: number) =>
  baseline > 0 ? (value / baseline).toFixed(1) : '—'

const filteredCases = computed<EvalCase[]>(() => {
  const cases = report.value?.cases ?? []
  if (caseFilter.value === 'MISS') {
    return cases.filter((item) => !item.hit)
  }
  if (caseFilter.value) {
    return cases.filter((item) => item.stratum === caseFilter.value)
  }
  return cases
})

const missCount = computed(() => report.value?.cases.filter((item) => !item.hit).length ?? 0)

const stratumLabel = (key: string) =>
  report.value?.strata.find((stratum) => stratum.key === key)?.label ?? key
</script>

<template>
  <div class="eval-page">
    <header class="eval-header">
      <p class="eyebrow">Retrieval Evaluation</p>
      <h1>检索质量评测</h1>
      <p class="subcopy">
        「AI 回答有依据」的前提是检索真能找对文档。这一页把这件事拆成可核对的数字：
        120 条标注查询按三档难度算出命中率与排序指标，对照随机基线；
        逐条明细里失败样本一条不藏，末段写明这批数字的边界。
      </p>
    </header>

    <ErrorState v-if="failed" message="评测报告加载失败，请重试" :on-retry="load" />

    <el-skeleton v-else-if="loading" :rows="8" animated />

    <template v-else-if="report">
      <!-- 快照元信息：这份数字「什么时候、因为什么」产生的 -->
      <section class="eval-meta" aria-label="快照信息">
        <div class="eval-meta__items">
          <span class="eval-meta__item">
            生成于 <b>{{ formatDateTime(report.generatedAt) }}</b>
          </span>
          <span class="eval-meta__item">{{ TRIGGER_LABEL[report.trigger] ?? report.trigger }}</span>
          <span class="eval-meta__item">
            语料 {{ report.corpus.documents }} 篇 × {{ report.corpus.cases }} 条
          </span>
          <span class="eval-meta__item">
            切片 {{ report.corpus.chunkSize }} / 重叠 {{ report.corpus.chunkOverlap }}
          </span>
        </div>
        <el-button
          v-if="isAdmin"
          type="primary"
          plain
          size="small"
          :loading="rerunning"
          @click="rerun"
        >
          重新运行
        </el-button>
      </section>

      <!-- 整体指标 + 随机基线对照 -->
      <section class="metric-grid" aria-label="整体指标">
        <article class="metric-card metric-card--hero">
          <p class="metric-card__label">Hit Rate@3</p>
          <p class="metric-card__value">
            {{ pct(report.overallAt3.hitRate) }}<span class="metric-card__unit">%</span>
          </p>
          <p class="metric-card__hint">
            随机基线 {{ pct(report.baseline.hitRateAt3) }}% ·
            <b>{{ times(report.overallAt3.hitRate, report.baseline.hitRateAt3) }}× </b>于随机
          </p>
        </article>
        <article class="metric-card">
          <p class="metric-card__label">MRR@3</p>
          <p class="metric-card__value">{{ fixed3(report.overallAt3.mrr) }}</p>
          <p class="metric-card__hint">相关文档的平均排位质量，越接近 1 越好</p>
        </article>
        <article class="metric-card">
          <p class="metric-card__label">NDCG@3</p>
          <p class="metric-card__value">{{ fixed3(report.overallAt3.ndcg) }}</p>
          <p class="metric-card__hint">整体排序质量，越靠前的命中权重越高</p>
        </article>
        <article class="metric-card">
          <p class="metric-card__label">Hit Rate@5</p>
          <p class="metric-card__value">
            {{ pct(report.overallAt5.hitRate) }}<span class="metric-card__unit">%</span>
          </p>
          <p class="metric-card__hint">
            随机基线 {{ pct(report.baseline.hitRateAt5) }}% · 放宽到 5 篇的提升
          </p>
        </article>
      </section>

      <!-- 三档分层：分数掉在哪一档，一眼可见 -->
      <section class="strata-panel" aria-label="分档表现">
        <header class="section-head">
          <h2>三档分层</h2>
          <p>
            同一批语料按查询难度分三档（各 {{ report.strata[0]?.metrics.caseCount ?? 40 }} 条）：
            字面档验证链路没坏，口语档与难例档才是真实用户提问的位置。
          </p>
        </header>
        <div class="strata-list">
          <article
            v-for="stratum in report.strata"
            :key="stratum.key"
            class="stratum"
            :style="{ '--stratum-color': STRATUM_META[stratum.key]?.color ?? 'var(--color-primary)' }"
          >
            <div class="stratum__head">
              <span class="stratum__label">{{ stratum.label }}</span>
              <span class="stratum__count">{{ stratum.metrics.caseCount }} 条</span>
            </div>
            <div
              class="stratum__bar"
              role="img"
              :aria-label="`Hit Rate ${pct(stratum.metrics.hitRate)}%`"
            >
              <div class="stratum__fill" :style="{ width: `${pct(stratum.metrics.hitRate)}%` }" />
            </div>
            <div class="stratum__numbers">
              <span
                >Hit Rate <b>{{ pct(stratum.metrics.hitRate) }}%</b></span
              >
              <span>MRR <b>{{ fixed3(stratum.metrics.mrr) }}</b></span>
              <span>NDCG <b>{{ fixed3(stratum.metrics.ndcg) }}</b></span>
            </div>
            <p class="stratum__desc">{{ STRATUM_META[stratum.key]?.desc }}</p>
          </article>
        </div>
      </section>

      <!-- 逐条明细：失败样本不做任何隐藏 -->
      <section class="cases-panel" aria-label="逐条明细">
        <header class="section-head section-head--row">
          <div>
            <h2>逐条明细</h2>
            <p>
              每条查询实际检索到了什么、命中与否，全部列出。共 {{ report.cases.length }} 条，
              其中 <b>{{ missCount }}</b> 条未命中 —— 未命中的行已标注，它们正是这页最该被看的部分。
            </p>
          </div>
          <el-radio-group v-model="caseFilter" size="small" class="cases-filter">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="MISS">仅未命中</el-radio-button>
            <el-radio-button
              v-for="stratum in report.strata"
              :key="stratum.key"
              :value="stratum.key"
            >
              {{ stratum.label }}
            </el-radio-button>
          </el-radio-group>
        </header>

        <p class="cases-count" aria-live="polite">
          显示 {{ filteredCases.length }} / {{ report.cases.length }} 条
        </p>

        <ol class="case-list">
          <li
            v-for="(item, index) in filteredCases"
            :key="`${item.stratum}-${index}`"
            class="case-row"
            :class="{ 'is-miss': !item.hit }"
          >
            <span class="case-row__rank" :title="item.hit ? `命中于第 ${item.hitRank} 位` : '未命中'">
              {{ item.hit ? item.hitRank : '未中' }}
            </span>
            <div class="case-row__body">
              <p class="case-row__query">{{ item.query }}</p>
              <p class="case-row__docs">
                <span class="case-row__docs-label">检索</span>
                {{ item.retrievedDocIds.join('、') || '（无结果）' }}
                <template v-if="!item.hit">
                  <span class="case-row__docs-label case-row__docs-label--expect">期望</span>
                  {{ item.relevantDocIds.join('、') }}
                </template>
              </p>
            </div>
            <span class="case-row__stratum">{{ stratumLabel(item.stratum) }}</span>
          </li>
        </ol>
      </section>

      <!--
        读数字的前提。这一段与后端 RetrievalEvalRunner / EvalFixtures 的文档是同一条约定，
        改数字口径时必须同步改这里 —— 页面上最容易被追问的就是这一段
      -->
      <section class="caveats" aria-label="数字的前提">
        <h2>读这些数字的前提</h2>
        <ul>
          <li>
            <b>定位是回归防线，不是质量结论。</b>
            语料与标注出自同一作者，三档难度梯度是刻意构造的 ——
            字面档的高分证明「链路没坏」，不证明「检索很强」。
          </li>
          <li>
            <b>没有留出集。</b>
            样本同时用于开发与门禁；缓解方式不是再加样本，而是本项目从未针对这批样本
            做过参数调优（无权重、系数或 topK 的搜索），不存在「调到样本上去」的路径。
          </li>
          <li>
            <b>与线上知识库是两套独立语料</b>
            （规模与主题分布接近、内容不重合），两组指标各自描述各自的语料，不可互相推算。
          </li>
          <li>
            <b>现场重跑只覆盖关键词路的底线</b>
            （确定性、可复现、毫秒级）；接入真实向量与重排后的对照数字需要模型调用，
            非确定性且要外部密钥，只能作为历史记录展示，不能现场重跑。
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.eval-page {
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: var(--ys-space-8);
  display: grid;
  gap: var(--ys-space-6);
}

.eval-header h1 {
  margin: var(--ys-space-1) 0;
  font-size: var(--ys-font-2xl);
}

.eyebrow {
  margin: 0;
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.subcopy {
  margin: 0;
  max-width: 780px;
  color: var(--color-text-secondary);
  line-height: var(--ys-leading-loose);
}

/* 快照元信息条 */
.eval-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-5);
  border: var(--card-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.eval-meta__items {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2) var(--ys-space-5);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.eval-meta__item b {
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  font-weight: 600;
}

/* 指标卡 */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ys-space-4);
}

.metric-card {
  display: grid;
  gap: var(--ys-space-2);
  align-content: start;
  padding: var(--card-padding);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  box-shadow: var(--card-shadow);
}

/* 主指标卡：整页要记住的那一个数 */
.metric-card--hero {
  border-color: var(--color-primary-border);
  background: linear-gradient(160deg, var(--color-primary-subtle), var(--color-bg-surface) 68%);
}

.metric-card__label {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.metric-card__value {
  margin: 0;
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-3xl);
  font-weight: 600;
  line-height: var(--ys-leading-tight);
  font-variant-numeric: tabular-nums;
}

.metric-card__unit {
  margin-left: 2px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-lg);
}

.metric-card__hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.metric-card__hint b {
  color: var(--color-primary);
}

/* 分层面板 */
.strata-panel,
.cases-panel,
.caveats {
  padding: var(--card-padding);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  box-shadow: var(--card-shadow);
}

.section-head h2 {
  margin: 0 0 var(--ys-space-1);
  font-size: var(--ys-font-lg);
}

.section-head p {
  margin: 0;
  max-width: 860px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.section-head--row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.strata-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ys-space-4);
  margin-top: var(--ys-space-5);
}

.stratum {
  display: grid;
  gap: var(--ys-space-2);
  align-content: start;
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.stratum__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-2);
}

.stratum__label {
  font-size: var(--ys-font-md);
  font-weight: 600;
}

.stratum__count {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.stratum__bar {
  height: 10px;
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.stratum__fill {
  height: 100%;
  border-radius: var(--ys-radius-full);
  background: var(--stratum-color);
  transition: width var(--ys-duration-slow) var(--ys-ease-out);
}

.stratum__numbers {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-1) var(--ys-space-4);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.stratum__numbers b {
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  font-weight: 600;
}

.stratum__desc {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

/* 逐条明细 */
.cases-filter {
  flex-wrap: wrap;
}

.cases-count {
  margin: var(--ys-space-3) 0 var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.case-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
}

.case-row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: var(--ys-space-3);
  align-items: start;
  padding: var(--ys-space-3) var(--ys-space-2);
  border-bottom: 1px solid var(--color-border);
}

.case-row:last-child {
  border-bottom: none;
}

/* 未命中行：左侧一条红标记 + 淡红底。它是这页最该被看到的部分，不该藏 */
.case-row.is-miss {
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-subtle);
  padding-left: var(--ys-space-3);
}

.case-row__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  padding: 0 var(--ys-space-1);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.case-row.is-miss .case-row__rank {
  background: var(--color-danger);
  color: var(--color-text-inverse);
}

.case-row__body {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.case-row__query {
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.case-row__docs {
  margin: 0;
  color: var(--color-text-secondary);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.case-row__docs-label {
  display: inline-block;
  margin-right: 4px;
  padding: 0 4px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
  font-family: var(--ys-font-sans);
}

.case-row__docs-label--expect {
  margin-left: var(--ys-space-2);
  background: var(--color-warning-subtle);
  color: var(--color-warning);
}

.case-row__stratum {
  padding: 1px 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--ys-radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  white-space: nowrap;
}

/* 前提声明：刻意做得显眼 —— 边界条件主动讲，而不是等被追问 */
.caveats {
  border-color: var(--color-border-strong);
}

.caveats h2 {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-font-lg);
}

.caveats ul {
  margin: 0;
  padding-left: var(--ys-space-5);
  display: grid;
  gap: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.caveats b {
  color: var(--color-text-primary);
}

@media (max-width: 960px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .strata-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .eval-page {
    padding: var(--ys-space-4);
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }

  .case-row {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .case-row__stratum {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
