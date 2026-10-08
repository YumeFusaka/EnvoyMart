import request from '@/utils/axios'
import type { KnowledgeSnippet } from '@/types/models'

// 回答质量评测：只展示真实 Agent 链路生成的快照。

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
  rootCause: string
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

export interface GroundingLiveCase {
  evidence?: KnowledgeSnippet[]
  /** 问题原文 */
  question: string
  /** 单条失败时为 null（失败原因在 error 里） */
  outcome: GroundingCaseOutcome | null
  answer: string | null
  evidenceCount: number
  latencyMs: number
  error: string | null
  retrievalQuery?: string | null
  expansion?: QueryExpansions | null
  toolExecutions?: ToolExecution[]
  retrievalTrace?: RetrievalTrace | null
}

export interface ToolExecution {
  tool: string
  input?: string
  output?: string
  success: boolean
  noData: boolean
  latencyMs: number
  evidence?: KnowledgeSnippet[]
}

/** 线上真跑：拿同一批问题重新问一遍当前 Agent。异步 + 单飞，前端轮询进度 */
export interface GroundingLiveRun {
  status: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'
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
  promptVersion?: string
  pipelineVersion?: string
}

/** 回答质量报告只返回真实链路快照。 */
export interface GroundingReport {
  live: GroundingLiveRun
}

/** 回答质量报告。公开只读，来源是最近一次真实链路快照。 */
export async function getGroundingReport() {
  const response = await request.get('/ai/eval/grounding/report')
  return response.data.data as GroundingReport
}

export async function runGroundingEval() {
  const response = await request.post('/ai/admin/eval/grounding/run')
  return response.data.data as GroundingLiveRun
}

// 生产链路检索评测（真实向量 + 图谱 + RRF + 重排 + 扩写，跑生产语料）。

export interface ProductionRetrievalMetrics {
  topK: number
  caseCount: number
  hitRate: number
  mrr: number
  ndcg: number
}

export interface ProductionRetrievalCorpus {
  documents: number
  cases: number
  topK: number
}

export interface ProductionRetrievalStratum {
  key: string
  label: string
  /** 这一档的口径说明（字面 / 口语换说法 / 跨文档），页面逐档展示 */
  note: string
  metrics: ProductionRetrievalMetrics
}

/** 一条样本的现场结果。retrievedTitles 把召回编号翻成标题，明细里给人看的是标题 */
export interface ProductionRetrievalCase {
  query: string
  stratum: string
  relevantDocIds: string[]
  retrievedDocIds: string[]
  hit: boolean
  hitRank: number
  graphRequired: boolean
  graphEvidenceHit: boolean
  retrievedTitles: Record<string, string>
  trace?: RetrievalTrace | null
  expansions?: QueryExpansions | null
  evidence?: KnowledgeSnippet[]
}

export interface RetrievalTrace {
  query: string
  vectorCandidates: number
  keywordCandidates: number
  graphCandidates: number
  fusedCandidates: number
  rerankedCandidates: number
  rerankQuery: string
  rerankApplied: boolean
}

export interface QueryExpansions {
  hypothetical?: string | null
  angles: string[]
}

export interface GraphPathMetrics {
  requiredCases: number
  hitCases: number
  hitRate: number
}

/**
 * 生产链路检索评测报告。
 *
 * status：NEVER（暂无快照）/ COMPLETED（有结果）/ FAILED（快照生成失败）。
 */
export interface ProductionRetrievalReport {
  status: 'NEVER' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  trigger: string | null
  generatedAt: string | null
  durationMs: number
  corpus: ProductionRetrievalCorpus
  /** 本次跑的是哪条链路 —— 页面照它向读者交代，不让人猜 */
  pipeline: string
  overall: ProductionRetrievalMetrics
  strata: ProductionRetrievalStratum[]
  cases: ProductionRetrievalCase[]
  graphPath: GraphPathMetrics
  error: string | null
}

/** 生产链路检索报告。公开只读。 */
export async function getProductionRetrievalReport() {
  const response = await request.get('/ai/eval/retrieval/report')
  return response.data.data as ProductionRetrievalReport
}

export async function runProductionRetrievalEval() {
  const response = await request.post('/ai/admin/eval/retrieval/run')
  return response.data.data as ProductionRetrievalReport
}
