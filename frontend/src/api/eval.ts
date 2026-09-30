import request from '@/utils/axios'

/**
 * 检索评测报告接口。读报告**公开**、重跑**仅管理员**，两个都是刻意的：
 * 「检索质量 0.633」是个对外的质量声明，让任何人打开报告、现场重跑核对，
 * 是它可信的全部理由；而重跑会替换全站共享的快照，属于改共享状态的管理动作，
 * 网关对 /admin 段强制登录、下游 @RequireAdmin 判角色，两层把关。
 */

/** 一组聚合指标。hitRate/mrr/ndcg 都是 [0,1] 的小数，展示层再转百分比 */
export interface EvalMetrics {
  topK: number
  caseCount: number
  hitRate: number
  mrr: number
  ndcg: number
}

export interface EvalCorpus {
  documents: number
  cases: number
  chunkSize: number
  chunkOverlap: number
}

/** 随机排序基线 —— 「63.3% 是高是低」的参照系，与实测值并排显示才有意义 */
export interface EvalBaseline {
  hitRateAt3: number
  hitRateAt5: number
}

export interface EvalStratum {
  key: string
  label: string
  metrics: EvalMetrics
}

/** 一条样本的现场结果。hit=false 的行是报告页上信息量最大的行 */
export interface EvalCase {
  query: string
  stratum: string
  relevantDocIds: string[]
  retrievedDocIds: string[]
  hit: boolean
  hitRank: number
}

export interface EvalRun {
  generatedAt: string
  /** STARTUP = 服务启动时自动生成；MANUAL = 管理台手动重跑 */
  trigger: string
  corpus: EvalCorpus
  overallAt3: EvalMetrics
  overallAt5: EvalMetrics
  baseline: EvalBaseline
  strata: EvalStratum[]
  cases: EvalCase[]
}

/** 最近一次评测快照（服务启动时生成；启动失败则本次现场补跑）。公开可读 */
export async function getEvalReport() {
  const response = await request.get('/knowledge/eval/report')
  return response.data.data as EvalRun
}

/** 现场重跑（毫秒级）并替换快照。仅管理员 */
export async function rerunEval() {
  const response = await request.post('/knowledge/admin/eval/run')
  return response.data.data as EvalRun
}

// ————————————————————————————————————————————————————————————————
// 回答质量评测（幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率）
//
// 与上面的检索评测是两个不同的量：检索评测回答「找得对不对」，这里回答
// 「答得有没有依据、该不该答」。两组数字都公开可读 —— 它们是质量声明，
// 让任何人自己核对是唯一可信的姿势；触发真跑要管理员（要花模型配额）。
// ————————————————————————————————————————————————————————————————

/** 用例类型。UNANSWERABLE 不是"更难"，是"期望行为不同"——该拒答而不是该答对 */
export type GroundingKind = 'ANSWERABLE' | 'UNANSWERABLE' | 'MULTI_HOP'

/**
 * 这条用例走了哪条判定路径。必须展示：
 * 「0 条未支撑」既可能是「逐句查过、全都干净」，也可能是「压根没判」，
 * 两件事在表格里长得一模一样。
 */
export type GroundingMode = 'PER_SENTENCE' | 'WHOLE_UNGROUNDED' | 'NOT_APPLICABLE'

/** 句子级引用检查的四种结果 */
export type CitationVerdict = 'OK' | 'WRONG_TARGET' | 'MISSING' | 'OUT_OF_RANGE'

export interface CitationCheck {
  sentence: string
  anchors: string[]
  /** 该句可用的引用编号（1 基）：自身的有效引用，没有时是所在块的有效引用 */
  refs: number[]
  verdict: CitationVerdict
}

export interface GroundingCaseOutcome {
  id: string
  kind: GroundingKind
  mode: GroundingMode
  answerable: boolean
  factSentences: number
  unsupported: number
  checks: number
  wrongCitations: number
  outOfRange: number
  /** 拒答门是否判成"拒答侧"（NONE / WEAK） */
  gateRefused: boolean
  refusalCorrect: boolean
  multiHopHit: boolean
  citations: CitationCheck[]
}

/** 四项指标。每项都带分母 —— 比例单看时读不出「3 条全对」还是「300 条全对」 */
export interface GroundingMetrics {
  caseCount: number
  hallucinationRate: number
  factSentences: number
  unsupportedSentences: number
  citationAccuracy: number
  citationChecks: number
  wrongCitations: number
  outOfRangeCitations: number
  refusalAccuracy: number
  /** 该拒未拒 —— 最危险的方向，单列 */
  missedRefusals: number
  /** 该答的题上判成依据不足，单列 */
  overRefusals: number
  multiHopHitRate: number
  multiHopCases: number
}

/** 离线重放：把夹具里的冻结答案喂给判定链。纯函数、毫秒级、每次读现算 */
export interface GroundingRun {
  generatedAt: string
  trigger: string
  /** 夹具采集时间 —— 必须跟着数字一起展示，否则旧答案会被读成"现在的表现" */
  capturedAt: string
  /** 采集时的对话模型 */
  model: string
  metrics: GroundingMetrics
  /** id → 问题原文。判定结果里没有它，逐条明细要显示 */
  questions: Record<string, string>
  cases: GroundingCaseOutcome[]
}

export interface GroundingLiveCase {
  /** 问题原文 */
  question: string
  /** 单条失败时为 null（失败原因在 error 里） */
  outcome: GroundingCaseOutcome | null
  answer: string | null
  evidenceCount: number
  latencyMs: number
  error: string | null
}

/** 线上真跑：拿同一批问题重新问一遍当前 Agent。异步 + 单飞，前端轮询进度 */
export interface GroundingLiveRun {
  status: 'IDLE' | 'RUNNING' | 'COMPLETED'
  startedAt: string | null
  finishedAt: string | null
  totalCases: number
  completedCases: number
  failedCases: number
  /** 正在跑的题目，进度条旁边显示 */
  currentQuestion: string | null
  /** 每轮独立用户：长期记忆按 userId 存，不复用才测得出"这批问题答得怎么样" */
  userId: string | null
  /** 只有全部跑完才有值：中途的指标是"越跑越像"的假数字 */
  metrics: GroundingMetrics | null
  cases: GroundingLiveCase[]
}

/** 一次响应并排两条链路，来源必须分别标注 */
export interface GroundingReport {
  offline: GroundingRun
  live: GroundingLiveRun
}

/** 回答质量报告。公开可读；offline 每次现算，live 是最近一次真跑 */
export async function getGroundingReport() {
  const response = await request.get('/ai/eval/grounding/report')
  return response.data.data as GroundingReport
}

/** 触发一次真实重跑（异步，约两分钟）。仅管理员；已在跑时返回当前状态而非排队 */
export async function runGroundingEval() {
  const response = await request.post('/ai/admin/eval/grounding/run')
  return response.data.data as GroundingLiveRun
}
