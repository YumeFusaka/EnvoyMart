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
