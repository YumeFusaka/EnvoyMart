<script setup lang="ts">
import { getGroundingReport, runGroundingEval } from '@/api/eval'
import type {
  CitationCheck,
  GroundingCaseOutcome,
  GroundingKind,
  GroundingMetrics,
  GroundingMode,
  GroundingReport,
  GroundingRun,
} from '@/api/eval'
import ErrorState from '@/components/ui/ErrorState.vue'
import { useUserStore } from '@/stores'
import { formatDateTime } from '@/utils/format'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref } from 'vue'

/**
 * 回答质量评测 —— 「AI 答得有没有依据、该不该答」的现场证据。
 *
 * 与检索质量评测是两个不同的量：那一页回答「找得对不对」（检索层），
 * 这一页回答「答得站不站得住」（生成层 + 拒答门 + 引用校验）。
 *
 * 页面的核心结构是**两组来源并排**，这个顺序是刻意的：
 *   1. 离线重放（CI 同源）—— 把真实模型输出的快照喂给判定链，可逐位复现、零成本，
 *      它测的是「判定逻辑有没有退化」，数字跟着夹具采集时间走；
 *   2. 线上真跑（当前系统）—— 拿同一批问题重新问一遍现在的 Agent + 知识库 + 图谱，
 *      跑一次两分钟、花真钱，只有管理员能触发；它测的是「系统现在表现如何」。
 * 两组数字同尺同算法（同一个判定器），所以能直接并排看；但**来源必须分别标注**——
 * 把旧快照的数字说成"当前质量"，是这页唯一会骗人的方式。
 *
 * 门槛（离线卡片上的徽标）与 `backend/agent-core/.../GroundingQualityTest.java`
 * 是同一组数：实测值下留一格。改动门槛必须两处同步，否则页面在替一个不存在的门禁背书。
 */

const userStore = useUserStore()
const isAdmin = computed(() => userStore.profile?.roleName === 'ADMIN')

const report = ref<GroundingReport | null>(null)
const loading = ref(true)
const failed = ref(false)
const triggering = ref(false)
const refreshing = ref(false)

/** 明细筛选：'' = 全部；'ISSUE' = 仅被判有问题的行；其余为用例类型 */
const caseFilter = ref('')

let timer: ReturnType<typeof setInterval> | undefined

async function load() {
  loading.value = true
  failed.value = false
  try {
    report.value = await getGroundingReport()
    syncPolling()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 静默刷新。真跑进行中每 3 秒拉一次进度；失败**不翻页面的错误态**——
 * 进度轮询的一次抖动不该把整页证据换成错误提示，保留最后一次已知状态即可。
 */
async function refresh() {
  refreshing.value = true
  try {
    report.value = await getGroundingReport()
    syncPolling()
  } catch {
    // 见上：静默
  } finally {
    refreshing.value = false
  }
}

/** 轮询开关跟着状态走：只在真跑进行中转，跑完立即停——不给服务端留多余的请求 */
function syncPolling() {
  const running = report.value?.live.status === 'RUNNING'
  if (running && !timer) {
    timer = setInterval(refresh, 3000)
  } else if (!running && timer) {
    clearInterval(timer)
    timer = undefined
  }
}

onMounted(load)

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})

/**
 * 触发一次真跑。接口返回的是「已开始」而不是结果——24 条用例串行调模型要两分钟，
 * 同步等会让浏览器先超时；之后由轮询推进度。
 */
async function triggerLive() {
  triggering.value = true
  try {
    await runGroundingEval()
    await refresh()
    ElMessage.success('已开始真实评测（约两分钟，逐条跑完自动刷新进度）')
  } catch {
    // 拦截器已弹提示；这里只负责恢复按钮
  } finally {
    triggering.value = false
  }
}

// ———————————————————————————— 展示帮手 ————————————————————————————

const pct = (value: number) => (value * 100).toFixed(1)

const KIND_META: Record<GroundingKind, { label: string; tone: string }> = {
  ANSWERABLE: { label: '该答', tone: 'ok' },
  MULTI_HOP: { label: '多跳', tone: 'accent' },
  UNANSWERABLE: { label: '该拒', tone: 'refuse' },
}

/** 判定路径必须显示：不显示的话，「0 条未支撑」既可能是查过全干净，也可能是压根没判 */
const MODE_META: Record<GroundingMode, string> = {
  PER_SENTENCE: '逐句判定',
  WHOLE_UNGROUNDED: '整篇无依据',
  NOT_APPLICABLE: '不适用',
}

const VERDICT_META: Record<CitationCheck['verdict'], { label: string; tone: string }> = {
  OK: { label: '引用正确', tone: 'ok' },
  WRONG_TARGET: { label: '引用指错', tone: 'danger' },
  MISSING: { label: '该引未引', tone: 'warn' },
  OUT_OF_RANGE: { label: '越界编号', tone: 'danger' },
}

type MetricKey = 'hallucination' | 'citation' | 'refusal' | 'multihop'

/**
 * 一张卡片一档门槛。这组数与 GroundingQualityTest 的门槛断言是同一组——
 * 改一处必须改两处，否则页面在替一个不存在的门禁背书。
 * 幻觉率是唯一"越低越好"的指标，`inverted` 把它与另外三项的通过判据区分开。
 */
const GATES: Record<MetricKey, { text: string; pass: (m: GroundingMetrics) => boolean }> = {
  hallucination: { text: '门槛 ≤ 15%', pass: (m) => m.hallucinationRate <= 0.15 },
  citation: { text: '门槛 ≥ 96%', pass: (m) => m.citationAccuracy >= 0.96 },
  refusal: { text: '门槛 ≥ 91%', pass: (m) => m.refusalAccuracy >= 0.91 },
  multihop: { text: '门槛 ≥ 66%', pass: (m) => m.multiHopHitRate >= 0.66 },
}

interface MetricRow {
  key: MetricKey
  label: string
  value: number
  inverted: boolean
  fraction: string
}

function metricRows(m: GroundingMetrics): MetricRow[] {
  return [
    {
      key: 'hallucination',
      label: '幻觉率',
      value: m.hallucinationRate,
      inverted: true,
      fraction: `${m.unsupportedSentences} / ${m.factSentences} 句事实句无出处`,
    },
    {
      key: 'citation',
      label: '引用准确率',
      value: m.citationAccuracy,
      inverted: false,
      fraction: `${m.citationChecks - m.wrongCitations} / ${m.citationChecks} 句引用指对位置`,
    },
    {
      key: 'refusal',
      label: '拒答准确率',
      value: m.refusalAccuracy,
      inverted: false,
      fraction: `该拒未拒 ${m.missedRefusals} · 该答却拒 ${m.overRefusals}`,
    },
    {
      key: 'multihop',
      label: '多跳命中率',
      value: m.multiHopHitRate,
      inverted: false,
      fraction: `${m.multiHopCases} 条跨文档用例`,
    },
  ]
}

const offlineRows = computed(() =>
  report.value ? metricRows(report.value.offline.metrics) : [],
)

const liveRows = computed(() => {
  const metrics = report.value?.live.metrics
  return metrics ? metricRows(metrics) : []
})

/** 真跑相对离线重放的差 —— 同尺同算法，这个差值才有意义。正数=更好（幻觉率反向） */
const liveDelta = computed<Partial<Record<MetricKey, number>>>(() => {
  const offline = report.value?.offline.metrics
  const live = report.value?.live.metrics
  if (!offline || !live) {
    return {}
  }
  return {
    hallucination: live.hallucinationRate - offline.hallucinationRate,
    citation: live.citationAccuracy - offline.citationAccuracy,
    refusal: live.refusalAccuracy - offline.refusalAccuracy,
    multihop: live.multiHopHitRate - offline.multiHopHitRate,
  }
})

/** 差值的三种读法都在这三个小函数里：有没有、好不好、怎么写 */
function deltaOf(key: MetricKey): number | null {
  return liveDelta.value[key] ?? null
}

function deltaTone(row: MetricRow): 'ok' | 'danger' {
  const delta = liveDelta.value[row.key] ?? 0
  return (row.inverted ? -delta : delta) >= 0 ? 'ok' : 'danger'
}

function deltaText(key: MetricKey): string {
  const delta = liveDelta.value[key] ?? 0
  return `与离线 ${delta >= 0 ? '+' : ''}${pct(delta)}pp`
}

/** 一行明细被判出问题没有 —— 是的话它必须显眼，这页最该被看到的就是这些行 */
function isIssue(c: GroundingCaseOutcome): boolean {
  return (
    c.unsupported > 0 ||
    c.wrongCitations > 0 ||
    c.outOfRange > 0 ||
    !c.refusalCorrect ||
    (c.kind === 'MULTI_HOP' && !c.multiHopHit)
  )
}

const issueCount = computed(
  () => report.value?.offline.cases.filter(isIssue).length ?? 0,
)

const filteredCases = computed<GroundingCaseOutcome[]>(() => {
  const cases = report.value?.offline.cases ?? []
  if (caseFilter.value === 'ISSUE') {
    return cases.filter(isIssue)
  }
  if (caseFilter.value) {
    return cases.filter((c) => c.kind === caseFilter.value)
  }
  return cases
})

/** 一条用例的结论摘要 —— 一行字说清判定链对它做了什么 */
function outcomeSummary(c: GroundingCaseOutcome): string {
  const parts: string[] = []
  if (c.unsupported > 0) {
    parts.push(`未支撑 ${c.unsupported}/${c.factSentences} 句`)
  }
  if (c.wrongCitations > 0) {
    parts.push(`引用指错 ${c.wrongCitations} 处`)
  }
  if (c.outOfRange > 0) {
    parts.push(`越界引用 ${c.outOfRange} 次`)
  }
  if (c.kind === 'UNANSWERABLE') {
    parts.push(c.refusalCorrect ? '拒答门判对' : c.gateRefused ? '不该拒却拒' : '该拒未拒')
  } else if (c.gateRefused) {
    parts.push('该答却判成依据不足')
  } else if (c.kind === 'MULTI_HOP') {
    parts.push(c.multiHopHit ? '多跳命中' : '要点未跨全')
  }
  return parts.length ? parts.join(' · ') : '无异常'
}

const offline = computed<GroundingRun | null>(() => report.value?.offline ?? null)
const live = computed(() => report.value?.live ?? null)

const liveProgress = computed(() => {
  const run = live.value
  if (!run || run.totalCases === 0) {
    return 0
  }
  return Math.round((run.completedCases / run.totalCases) * 100)
})
</script>

<template>
  <div class="quality-page">
    <header class="quality-header">
      <p class="eyebrow">Answer Quality</p>
      <h1>回答质量评测</h1>
      <p class="subcopy">
        检索质量评测回答「找得对不对」，这一页回答后半个问题：<b>答得有没有依据、该不该答</b>。
        24 条标注问题分三类（该答 / 多跳 / 该拒），用同一套判定链算出四项指标 ——
        幻觉率、引用准确率、拒答准确率、多跳命中率，每一项都带分母，失败样本逐条列出。
      </p>
    </header>

    <ErrorState v-if="failed" message="回答质量报告加载失败，请重试" :on-retry="load" />

    <el-skeleton v-else-if="loading" :rows="8" animated />

    <template v-else-if="report && offline">
      <!-- ============ 第一组：离线重放（CI 同源） ============ -->
      <section class="block" aria-label="离线重放">
        <header class="block__head">
          <div>
            <h2>
              离线重放
              <span class="badge badge--tone-ok">CI 门禁同源</span>
            </h2>
            <p>
              把<b>真实模型输出的快照</b>喂给判定链重放 —— 不碰模型、不碰知识库，
              可逐位复现、零成本。它测的是「判定逻辑有没有退化」，
              数字跟着下面这个采集时间走，不是「现在的模型」的表现。
            </p>
          </div>
        </header>

        <div class="meta-strip">
          <span class="meta-strip__item">
            答案采集于 <b>{{ formatDateTime(offline.capturedAt) }}</b>
          </span>
          <span class="meta-strip__item">模型 <b>{{ offline.model }}</b></span>
          <span class="meta-strip__item">本次重放 <b>{{ formatDateTime(offline.generatedAt) }}</b></span>
          <span class="meta-strip__item">
            越界引用
            <b :class="{ 'is-danger': offline.metrics.outOfRangeCitations > 0 }">
              {{ offline.metrics.outOfRangeCitations }}
            </b>
            <span class="meta-strip__note">（门槛 = 0，引了不存在的编号即编造）</span>
          </span>
        </div>

        <div class="metric-grid">
          <article
            v-for="row in offlineRows"
            :key="row.key"
            class="metric-card"
            :class="{ 'metric-card--hero': row.key === 'hallucination' }"
          >
            <div class="metric-card__top">
              <p class="metric-card__label">{{ row.label }}</p>
              <span
                class="badge"
                :class="GATES[row.key].pass(offline.metrics) ? 'badge--tone-ok' : 'badge--tone-danger'"
              >
                {{ GATES[row.key].pass(offline.metrics) ? '过' : '未过' }} · {{ GATES[row.key].text }}
              </span>
            </div>
            <p class="metric-card__value">
              {{ pct(row.value) }}<span class="metric-card__unit">%</span>
            </p>
            <p class="metric-card__hint">{{ row.fraction }}</p>
          </article>
        </div>
      </section>

      <!-- ============ 第二组：线上真跑（当前系统） ============ -->
      <section class="block" aria-label="线上真跑">
        <header class="block__head block__head--row">
          <div>
            <h2>
              线上真跑
              <span class="badge badge--tone-accent">当前系统</span>
            </h2>
            <p>
              拿同一批问题重新问一遍<b>现在的 Agent + 知识库 + 图谱</b>，同一套判定算同一组指标 ——
              所以两组的数字可以直接并排。跑一轮约两分钟、要花模型配额，
              只在管理员点按钮时跑；每条用例独立用户独立会话，长期记忆不掺进来。
            </p>
          </div>
          <div class="live-actions">
            <el-button
              v-if="live && live.status !== 'RUNNING'"
              size="small"
              :loading="refreshing"
              @click="refresh"
            >
              刷新状态
            </el-button>
            <!-- 首跑的入口只在下面的空态里（那里能把「跑什么、花多少」讲清楚）；
                 这里只在跑完之后出现，语义是重跑。两处都给按钮会让 IDLE 状态出现两个
                 一模一样的「开始真跑」 -->
            <el-button
              v-if="isAdmin && live && live.status === 'COMPLETED'"
              type="primary"
              size="small"
              :loading="triggering"
              @click="triggerLive"
            >
              重新真跑
            </el-button>
          </div>
        </header>

        <!-- 从未跑过：空态讲清楚这里将出现什么，而不是一片留白 -->
        <div v-if="live && live.status === 'IDLE'" class="live-empty">
          <p>
            还没有人跑过真实评测。它会把 {{ live.totalCases }} 条问题在当前模型上重新问一遍，
            结果与上面这组数字并排显示（含差值），用来回答「现在的系统表现如何」。
          </p>
          <p v-if="!isAdmin" class="live-empty__hint">
            触发真跑要花模型配额，只有管理员可以发起 —— 任何人都能在结果出来后回来看。
          </p>
          <el-button
            v-else
            type="primary"
            plain
            :loading="triggering"
            @click="triggerLive"
          >
            开始真跑（约两分钟）
          </el-button>
        </div>

        <!-- 跑动中：进度 + 当前题目。指标不显示 —— 中途的数字是「越跑越像」的假数字 -->
        <div v-else-if="live && live.status === 'RUNNING'" class="live-running" aria-live="polite">
          <div class="live-running__row">
            <span class="live-running__count">
              {{ live.completedCases }} / {{ live.totalCases }} 条
            </span>
            <span class="live-running__hint">正在真实调用模型，逐条判定中…</span>
          </div>
          <el-progress :percentage="liveProgress" :stroke-width="10" :show-text="false" />
          <p v-if="live.currentQuestion" class="live-running__question">
            当前题目：{{ live.currentQuestion }}
          </p>
        </div>

        <!-- 跑完：与离线同尺的四个数 + 差值 -->
        <template v-else-if="live && live.status === 'COMPLETED'">
          <div class="meta-strip">
            <span class="meta-strip__item">
              跑于 <b>{{ formatDateTime(live.finishedAt) }}</b>
            </span>
            <span class="meta-strip__item">
              完成 <b>{{ live.completedCases }}</b> / {{ live.totalCases }} 条
            </span>
            <span v-if="live.failedCases > 0" class="meta-strip__item is-danger">
              其中 <b>{{ live.failedCases }}</b> 条调用失败（已从分母摘除，见下方明细）
            </span>
          </div>

          <div v-if="liveRows.length" class="metric-grid">
            <article v-for="row in liveRows" :key="row.key" class="metric-card">
              <div class="metric-card__top">
                <p class="metric-card__label">{{ row.label }}</p>
                <span
                  v-if="deltaOf(row.key) !== null"
                  class="badge"
                  :class="`badge--tone-${deltaTone(row)}`"
                >
                  {{ deltaText(row.key) }}
                </span>
              </div>
              <p class="metric-card__value">
                {{ pct(row.value) }}<span class="metric-card__unit">%</span>
              </p>
              <p class="metric-card__hint">{{ row.fraction }}</p>
            </article>
          </div>

          <!-- 逐条：问题 + 真实回答。指标是摘要，答案才是证据 -->
          <details class="live-cases">
            <summary>逐条真实回答（{{ live.cases.length }} 条）</summary>
            <ol class="live-cases__list">
              <li v-for="(item, index) in live.cases" :key="index" class="live-case">
                <div class="live-case__head">
                  <span class="mono">{{ item.outcome?.id ?? '—' }}</span>
                  <span v-if="item.outcome" class="badge" :class="`badge--tone-${KIND_META[item.outcome.kind].tone}`">
                    {{ KIND_META[item.outcome.kind].label }}
                  </span>
                  <span class="live-case__question">{{ item.question }}</span>
                  <span class="live-case__latency">{{ item.latencyMs }}ms</span>
                </div>
                <p v-if="item.error" class="live-case__error">调用失败：{{ item.error }}</p>
                <p v-else class="live-case__answer">{{ item.answer }}</p>
                <p v-if="item.outcome" class="live-case__verdict">{{ outcomeSummary(item.outcome) }}</p>
              </li>
            </ol>
          </details>
        </template>
      </section>

      <!-- ============ 逐条明细（离线重放） ============ -->
      <section class="block" aria-label="逐条明细">
        <header class="block__head block__head--row">
          <div>
            <h2>逐条明细</h2>
            <p>
              判定链对每条用例做了什么，逐条列出。共 {{ offline.cases.length }} 条，
              其中 <b>{{ issueCount }}</b> 条被判出问题 —— 它们已标注，正是这页最该被看的部分。
            </p>
          </div>
          <el-radio-group v-model="caseFilter" size="small" class="cases-filter">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="ISSUE">仅有问题</el-radio-button>
            <el-radio-button value="ANSWERABLE">该答</el-radio-button>
            <el-radio-button value="MULTI_HOP">多跳</el-radio-button>
            <el-radio-button value="UNANSWERABLE">该拒</el-radio-button>
          </el-radio-group>
        </header>

        <p class="cases-count" aria-live="polite">
          显示 {{ filteredCases.length }} / {{ offline.cases.length }} 条
        </p>

        <ol class="case-list">
          <li
            v-for="item in filteredCases"
            :key="item.id"
            class="case-row"
            :class="{ 'is-issue': isIssue(item) }"
          >
            <div class="case-row__head">
              <span class="case-row__id mono">{{ item.id }}</span>
              <span class="badge" :class="`badge--tone-${KIND_META[item.kind].tone}`">
                {{ KIND_META[item.kind].label }}
              </span>
              <span class="case-row__mode" :title="MODE_META[item.mode]">
                {{ MODE_META[item.mode] }}
              </span>
              <span class="case-row__question">
                {{ offline.questions[item.id] ?? '（问题原文缺失）' }}
              </span>
            </div>
            <p class="case-row__summary" :class="{ 'is-issue': isIssue(item) }">
              {{ outcomeSummary(item) }}
            </p>

            <!-- 有引用检查的行：逐句展开判定链看到的东西 -->
            <details v-if="item.citations.length" class="checks">
              <summary>逐句引用判定（{{ item.citations.length }} 句）</summary>
              <ul class="checks__list">
                <li v-for="(check, index) in item.citations" :key="index" class="check">
                  <span class="badge" :class="`badge--tone-${VERDICT_META[check.verdict].tone}`">
                    {{ VERDICT_META[check.verdict].label }}
                  </span>
                  <div class="check__body">
                    <p class="check__sentence">{{ check.sentence }}</p>
                    <p class="check__meta">
                      锚点 <b>{{ check.anchors.join('、') }}</b>
                      <template v-if="check.refs.length">
                        · 可用引用 <b>[{{ check.refs.join('] [') }}]</b>
                      </template>
                      <template v-else>· 无可用引用</template>
                    </p>
                  </div>
                </li>
              </ul>
            </details>
          </li>
        </ol>
      </section>

      <!-- ============ 读这些数字的前提 ============ -->
      <section class="caveats" aria-label="数字的前提">
        <h2>读这些数字的前提</h2>
        <ul>
          <li>
            <b>两组数字来源不同，不许混着讲。</b>
            离线重放的数字来自
            {{ formatDateTime(offline.capturedAt) }} 采集的模型输出快照——
            它回答「判定逻辑有没有退化」；「现在的模型表现如何」只有真跑那一组能回答，
            而真跑是某一时刻的一次抽样，不是长期均值。
          </li>
          <li>
            <b>没有模型判官。</b>
            判定全部是确定性的：引用核对靠锚点在证据里逐字比对，拒答准确率只看门的判定。
            不给模型自己判自己打分的机会——自评有自我偏好，会给虚假的质量信号。
          </li>
          <li>
            <b>门槛是回归防线，不是质量结论。</b>
            样本量 24 条，一条用例翻转就是 4 个百分点，所以门槛按「实测值下留一格」设，
            不按行业基准设。门槛与 CI 门禁是同一组数字（判定逻辑回归则门禁变红）。
          </li>
          <li>
            <b>「该拒」的用例只看一件事：门判得对不对。</b>
            它答了什么不进另外三项的账——把「拒答题上答了话」再罚一次，
            等于用一个错误重复扣分。
          </li>
          <li>
            <b>工具轮次不进幻觉率的账。</b>
            跑过工具的轮次，事实来自工具返回，没有知识库角标可标——那是「不适用」，
            不是「编造」。判据以「答案里有没有有效引用」为准，与运行时闸门同义。
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.quality-page {
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: var(--ys-space-8);
  display: grid;
  gap: var(--ys-space-6);
}

.quality-header h1 {
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
  max-width: 820px;
  color: var(--color-text-secondary);
  line-height: var(--ys-leading-loose);
}

.subcopy b {
  color: var(--color-text-primary);
}

.mono {
  font-family: var(--ys-font-mono);
}

/* 区块容器 */
.block,
.caveats {
  padding: var(--card-padding);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  box-shadow: var(--card-shadow);
}

.block__head h2 {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-1);
  font-size: var(--ys-font-lg);
}

.block__head p {
  margin: 0;
  max-width: 880px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.block__head p b {
  color: var(--color-text-primary);
}

.block__head--row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border: 1px solid transparent;
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-font-xs);
  font-weight: 600;
  white-space: nowrap;
}

.badge--tone-ok {
  border-color: var(--ys-green-600);
  background: var(--color-success-subtle);
  color: var(--ys-green-600);
}

.badge--tone-danger {
  border-color: var(--color-danger);
  background: var(--color-danger-subtle);
  color: var(--color-danger);
}

.badge--tone-warn {
  border-color: var(--color-warning-strong);
  background: var(--color-warning-subtle);
  color: var(--color-warning-strong);
}

.badge--tone-accent {
  border-color: var(--color-accent);
  background: var(--color-accent-subtle);
  color: var(--color-accent-hover);
}

.badge--tone-refuse {
  border-color: var(--color-border-strong);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
}

/* 元信息条 */
.meta-strip {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2) var(--ys-space-5);
  margin: var(--ys-space-4) 0;
  padding: var(--ys-space-3) var(--ys-space-4);
  border: var(--card-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.meta-strip__item b {
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  font-weight: 600;
}

.meta-strip__item.is-danger b,
.meta-strip__item b.is-danger {
  color: var(--color-danger);
}

.meta-strip__note {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
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
  padding: var(--ys-space-5);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
}

/* 主指标卡：这一组里最该记住的那个数 */
.metric-card--hero {
  border-color: var(--color-primary-border);
  background: linear-gradient(160deg, var(--color-primary-subtle), var(--color-bg-surface) 68%);
}

.metric-card__top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-1);
}

.metric-card__label {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.08em;
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

/* 真跑状态机 */
.live-actions {
  display: flex;
  gap: var(--ys-space-2);
}

.live-empty {
  display: grid;
  gap: var(--ys-space-3);
  justify-items: start;
  margin-top: var(--ys-space-4);
  padding: var(--ys-space-5);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.live-empty p {
  margin: 0;
  max-width: 760px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.live-empty__hint {
  color: var(--color-text-muted) !important;
  font-size: var(--ys-font-xs) !important;
}

.live-running {
  display: grid;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-4);
  padding: var(--ys-space-5);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-primary-subtle);
}

.live-running__row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-2);
}

.live-running__count {
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-md);
  font-weight: 600;
}

.live-running__hint {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.live-running__question {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  overflow-wrap: anywhere;
}

/* 真跑逐条回答 */
.live-cases {
  margin-top: var(--ys-space-4);
  border: var(--card-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.live-cases summary {
  padding: var(--ys-space-3) var(--ys-space-4);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
}

.live-cases__list {
  margin: 0;
  padding: 0 var(--ys-space-4) var(--ys-space-2);
  list-style: none;
  display: grid;
}

.live-case {
  display: grid;
  gap: var(--ys-space-1);
  padding: var(--ys-space-3) 0;
  border-top: 1px solid var(--color-border);
}

.live-case__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
  font-size: var(--ys-font-xs);
}

.live-case__question {
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  overflow-wrap: anywhere;
}

.live-case__latency {
  margin-left: auto;
  color: var(--color-text-muted);
  font-family: var(--ys-font-mono);
}

.live-case__answer {
  margin: 0;
  padding: var(--ys-space-2) var(--ys-space-3);
  border-left: 3px solid var(--color-border-strong);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.live-case__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--ys-font-xs);
}

.live-case__verdict {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
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
  gap: var(--ys-space-1);
  padding: var(--ys-space-3) var(--ys-space-2);
  border-bottom: 1px solid var(--color-border);
}

.case-row:last-child {
  border-bottom: none;
}

/* 被判出问题的行：左侧红标记 + 淡红底。这页最该被看到的就是它们 */
.case-row.is-issue {
  border-left: 3px solid var(--color-danger);
  background: var(--color-danger-subtle);
  padding-left: var(--ys-space-3);
}

.case-row__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.case-row__id {
  font-size: var(--ys-font-xs);
  font-weight: 600;
  color: var(--color-text-secondary);
}

.case-row__mode {
  padding: 1px 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--ys-radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  white-space: nowrap;
}

.case-row__question {
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.case-row__summary {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.case-row__summary.is-issue {
  color: var(--color-danger);
  font-weight: 600;
}

/* 逐句引用判定 */
.checks {
  margin-top: var(--ys-space-1);
}

.checks summary {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.checks__list {
  margin: var(--ys-space-2) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--ys-space-2);
}

.check {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--ys-space-2);
  align-items: start;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
}

.check__body {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.check__sentence {
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.check__meta {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  overflow-wrap: anywhere;
}

.check__meta b {
  font-family: var(--ys-font-mono);
  color: var(--color-text-primary);
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
}

@media (max-width: 640px) {
  .quality-page {
    padding: var(--ys-space-4);
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }

  .check {
    grid-template-columns: 1fr;
  }
}
</style>
