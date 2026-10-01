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

/**
 * 扩写对照的每一行：同一个档位，扩写前与扩写后并排。
 *
 * 以「扩写前」那一份为主表遍历，扩写后按 key 取同档 —— 两边都来自同一次运行、
 * 同一批样本，档位集合一致；取不到时按 0 显示而不是崩掉，页面上少一个数字
 * 总好过整页打不开。
 */
const expansionRows = computed(() => {
  const run = report.value
  if (!run?.expansion) {
    return []
  }
  const after = new Map(run.expansion.strata.map((stratum) => [stratum.key, stratum.metrics]))
  return run.strata.map((stratum) => {
    const beforeRate = stratum.metrics.hitRate
    const afterRate = after.get(stratum.key)?.hitRate ?? 0
    return {
      key: stratum.key,
      label: stratum.label,
      before: beforeRate,
      after: afterRate,
      deltaPp: (afterRate - beforeRate) * 100,
    }
  })
})

/** 全量那一行的百分点差 —— 页面顶上的主数字，与分档表同一个算法 */
const expansionOverallDelta = computed(() => {
  const run = report.value
  if (!run?.expansion) {
    return 0
  }
  return (run.expansion.overallAt3.hitRate - run.overallAt3.hitRate) * 100
})

/** 百分点差带符号显示。0 也要显式写成 0.0 —— 空着会被读成"没测" */
const signedPp = (delta: number) => `${delta > 0 ? '+' : ''}${delta.toFixed(1)}pp`

const deltaClass = (delta: number) =>
  delta > 0.05 ? 'is-up' : delta < -0.05 ? 'is-down' : 'is-flat'
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

      <!--
        扩写对照。位置在分档与逐条明细之间：读者刚看完"分数掉在哪一档"，
        紧接着就能看到"扩写把哪一档抬起来了多少"——这是同一件事的下半句。
      -->
      <section v-if="report.expansion" class="expansion-panel" aria-label="查询扩写对照">
        <header class="section-head">
          <h2>查询扩写对照</h2>
          <p>
            同一批 {{ report.corpus.cases }} 条查询、同一条关键词路，唯一变量是检索前
            先让模型把问题换个说法重问一遍。用户说「东西还没到」，文档写「配送时效」——
            一个词都对不上，换个说法，字面才有可能撞上。
          </p>
        </header>

        <div class="expansion-hero">
          <div>
            <p class="expansion-hero__label">全量 Hit Rate@3</p>
            <p class="expansion-hero__pair">
              <span class="expansion-hero__value">{{ pct(report.overallAt3.hitRate) }}%</span>
              <span class="expansion-hero__arrow" aria-hidden="true">→</span>
              <span class="expansion-hero__value expansion-hero__value--after"
                >{{ pct(report.expansion.overallAt3.hitRate) }}%</span
              >
            </p>
          </div>
          <span class="expansion-delta" :class="deltaClass(expansionOverallDelta)">
            {{ signedPp(expansionOverallDelta) }}
          </span>
        </div>

        <ul class="expansion-list">
          <li
            v-for="row in expansionRows"
            :key="row.key"
            class="expansion-row"
            :style="{ '--stratum-color': STRATUM_META[row.key]?.color ?? 'var(--color-primary)' }"
          >
            <span class="expansion-row__label">{{ row.label }}</span>
            <div class="expansion-row__bars">
              <div
                class="expansion-row__bar"
                role="img"
                :aria-label="`扩写前 ${pct(row.before)}%`"
              >
                <div class="expansion-row__fill" :style="{ width: `${pct(row.before)}%` }" />
              </div>
              <div
                class="expansion-row__bar expansion-row__bar--after"
                role="img"
                :aria-label="`扩写后 ${pct(row.after)}%`"
              >
                <div class="expansion-row__fill" :style="{ width: `${pct(row.after)}%` }" />
              </div>
            </div>
            <span class="expansion-row__numbers">
              <b>{{ pct(row.before) }}%</b>
              <span class="expansion-row__arrow" aria-hidden="true">→</span>
              <b>{{ pct(row.after) }}%</b>
            </span>
            <span class="expansion-delta" :class="deltaClass(row.deltaPp)">
              {{ signedPp(row.deltaPp) }}
            </span>
          </li>
        </ul>

        <p class="expansion-note">
          扩写数据是<b>预录夹具</b>：{{ report.expansion.recordedQueries }} 条查询，
          由 <b>{{ report.expansion.model }}</b> 于
          {{ formatDateTime(report.expansion.capturedAt) }} 采集，现场重跑读的是同一份快照。
          这一栏只体现<b>角度改写</b>那一半的收益 —— 假想答案（HyDE）补的是语义路的词汇鸿沟，
          要连真实向量服务才看得见，见下方「读这些数字的前提」。
        </p>
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
          <li>
            <b>扩写对照同样只走关键词路，且扩写数据是预录的。</b>
            也就是说这两条数字里<b>没有 HyDE 的功劳</b> —— 假想答案要送进真实向量才算数，
            这里的向量库是确定性替身。扩写栏真正的贡献是「换个说法重问」那一条路，
            以及一个可以反复复现的对照。
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
.expansion-panel,
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

/* 扩写对照 */
.expansion-hero {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-bottom: var(--ys-space-4);
  padding: var(--ys-space-4);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-md);
  background: linear-gradient(120deg, var(--color-primary-subtle), var(--color-bg-surface) 70%);
}

.expansion-hero__label {
  margin: 0 0 var(--ys-space-1);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.expansion-hero__pair {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
  margin: 0;
  font-family: var(--ys-font-mono);
  font-variant-numeric: tabular-nums;
}

.expansion-hero__value {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xl);
  font-weight: 600;
}

.expansion-hero__value--after {
  color: var(--color-primary);
  font-size: var(--ys-font-3xl);
}

.expansion-hero__arrow {
  color: var(--color-text-muted);
}

.expansion-list {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0 0 var(--ys-space-4);
  padding: 0;
  list-style: none;
}

/* 四列：档位名 / 双条 / 数字 / 增益。窄屏塌成两行，见下方媒体查询 */
.expansion-row {
  display: grid;
  grid-template-columns: 5.5rem minmax(0, 1fr) 9.5rem 4.5rem;
  align-items: center;
  gap: var(--ys-space-3);
}

.expansion-row__label {
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.expansion-row__bars {
  display: grid;
  gap: 3px;
}

.expansion-row__bar {
  height: 8px;
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.expansion-row__bar .expansion-row__fill {
  height: 100%;
  border-radius: var(--ys-radius-full);
  background: var(--color-text-muted);
  transition: width var(--ys-duration-slow) var(--ys-ease-out);
}

/* 扩写后那条用档位自己的颜色：一眼能看出"变长的是哪一条" */
.expansion-row__bar--after .expansion-row__fill {
  background: var(--stratum-color);
}

.expansion-row__numbers {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
}

.expansion-row__numbers b {
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  font-weight: 600;
}

.expansion-row__arrow {
  margin: 0 var(--ys-space-1);
  color: var(--color-text-muted);
}

/* 增益徽标。三个状态各有颜色 —— 掉了要看得出来，这是"代价上限"的可见形式 */
.expansion-delta {
  justify-self: start;
  padding: 2px var(--ys-space-2);
  border-radius: var(--ys-radius-full);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.expansion-delta.is-up {
  color: var(--color-success);
  background: var(--color-success-subtle);
}

.expansion-delta.is-down {
  color: var(--color-danger);
  background: var(--color-danger-subtle);
}

.expansion-delta.is-flat {
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
}

.expansion-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.expansion-note b {
  color: var(--color-text-primary);
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

  /* 双条太窄就读不出长短了。把数字与增益挪到第二行，条占满整行 */
  .expansion-row {
    grid-template-columns: 5.5rem minmax(0, 1fr) auto;
  }

  .expansion-row__bars {
    grid-column: 2 / -1;
  }

  .expansion-row__numbers {
    grid-column: 2;
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

  .expansion-hero {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
