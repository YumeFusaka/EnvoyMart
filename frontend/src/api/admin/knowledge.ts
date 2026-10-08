import request from '@/utils/axios'
import type { DocumentDetail, DocumentSummary, PageResult } from '@/types/models'

/**
 * 知识库管理接口。
 * <p>
 * 写接口在 `/knowledge/admin/` 段下 —— 网关的 `ADMIN_SEGMENT` 会对任何含该段的路径
 * 强制登录，服务端再按角色判一次。读接口仍是公开的（`/knowledge/documents`），
 * 因为「引用要让任何人能自己核对」这条不因为多了个管理台而改变。
 */

/** 新建或按编号更新一篇文档。docNo 留空即新建，返回落定的编号 */
export async function upsertDocument(payload: {
  docNo?: string
  title: string
  source: string
  scope: string
  version?: string
  tags?: string[]
  content: string
  subjectSpuIds?: number[]
}) {
  const response = await request.post('/knowledge/admin/documents', payload)
  return response.data.data as string
}

/** 停用（0）/ 启用（1）。停用只让它退出检索，不删数据 —— 删了会让已发出的引用点开是 404 */
export async function changeDocumentStatus(docNo: string, status: number) {
  await request.put(`/knowledge/admin/documents/${docNo}/status`, null, { params: { status } })
}

/**
 * 重建检索索引 —— 知识库改完之后必须调它，否则新文档进不了向量库与图谱。
 * <p>
 * 异步：返回的是「已开始」。要等它跑完看 {@link fetchReindexStatus}。
 * 重建期间检索是空的（先清空再写入），所以管理台必须把这段状态显示出来。
 */
export async function reindexKnowledge() {
  const response = await request.post('/ai/admin/knowledge/reindex')
  return response.data.data as {
    running: boolean
    error?: string | null
  }
}

/**
 * 单篇增量重建 —— 上传、编辑、停用一篇文档后调用，让这一篇立刻生效。
 * <p>
 * 与全量 {@link reindexKnowledge} 的区别只在代价：全量要重编码整库、重抽全量图谱
 * （七十几秒、十几次模型调用），单篇只处理这一篇（秒级、两次调用）。
 * <p>
 * 调用时机是**写操作成功之后**，不能省：「文档改了但 AI 不知道」在界面上看不出来，
 * 用户只会觉得 AI 答得不对。停用也走它——传进来的编号已不在语料里时会执行删除。
 */
export async function reindexDocument(docNo: string) {
  const response = await request.post(`/ai/admin/knowledge/reindex/${encodeURIComponent(docNo)}`)
  return response.data.data as {
    docNo: string
    deletedOnly: boolean
    chunkCount: number
    totalChunks: number
    graphUpdated: boolean
  }
}

export async function fetchReindexStatus() {
  const response = await request.get('/ai/admin/knowledge/status')
  return response.data.data as {
    running: boolean
    startedAt?: string | null
    finishedAt?: string | null
    error?: string | null
    result?: {
      documentCount?: number
      chunkCount?: number
      graphEdges?: number
    } | null
  }
}

/**
 * 列表复用公开读接口，这里再导出一次只是为了让管理台只 import 这一个模块。
 * <p>
 * 适配成 `PageResult` 形状是刻意的：`/knowledge/documents` 一次返回全部
 * （语料是几十篇的量级，分页只会让切分与图谱多出「拉到一半」的中间态），
 * 但管理台的 `useAdminList` 要的是分页形状。在这里补齐 `total` 并**在页面里
 * 不渲染分页器** —— 比在页面上假装分页、点第二页永远为空要诚实。
 */
export async function listDocumentsAdmin(params: {
  scope?: string
  keyword?: string
  status?: number
}): Promise<PageResult<DocumentSummary>> {
  const response = await request.get('/knowledge/documents', { params })
  const records = (response.data.data ?? []) as DocumentSummary[]
  return {
    records,
    total: records.length,
    page: 0,
    size: records.length,
    // 一次给全部，所以永远没有下一页 —— 页面据此不渲染分页器
    hasNext: false,
  }
}

/**
 * 某个商品当前被哪些文档支持 —— 商品编辑页的「说明书」面板读它。
 *
 * 读的是**图**不是文档表：商品与说明书在库里没有外键，绑定是构建期实体链接的结果。
 * 返回空数组是正常的中间态（说明书还没传、或正文没提到这个商品），不是错误 ——
 * 页面要把它显示成「还没有说明书」，而不是一次失败。
 */
export async function getProductDocuments(spuKey: string) {
  const response = await request.get(`/knowledge/admin/products/${encodeURIComponent(spuKey)}/documents`)
  return (response.data.data ?? []) as { docNo: string; title: string; relations: number }[]
}

export async function getDocumentAdmin(docNo: string) {
  const response = await request.get(`/knowledge/documents/${docNo}`)
  return response.data.data as DocumentDetail
}

/** 文档明确归属的商品。说明书绑定读关联表，不依赖图谱抽取是否成功。 */
export async function getDocumentBindings(docNo: string) {
  const response = await request.get(`/knowledge/admin/documents/${encodeURIComponent(docNo)}/bindings`)
  return (response.data.data ?? []) as number[]
}

/**
 * 商品资料覆盖率 —— 「在售商品里多少是有说明书支撑的」。
 *
 * 这个数字是**存储质量的欠账表**：未覆盖的商品，用户问到时系统只能答
 * 「知识库里没有」。所以它不是一个只读好看的指标，而是一份待办清单。
 *
 * `available=false` 必须先于三个计数被读取：图谱或商品目录不可用时三个计数都是 0，
 * 与「全部覆盖」在界面上长得一模一样，而它们是相反的两句话。
 */
export async function fetchKnowledgeCoverage() {
  const response = await request.get('/ai/admin/knowledge/coverage')
  return response.data.data as {
    totalSpu: number
    coveredSpu: number
    uncoveredSpu: { spuKey: string; name: string; reason: 'NO_NODE' | 'NO_DOCUMENT' }[]
    available: boolean
    reason?: string | null
  }
}

export type GraphBuildFailure = {
  id: number
  batchId: string
  docNo?: string | null
  entityKey?: string | null
  stage: string
  reasonCode: string
  detail?: string | null
  retryable: boolean
  occurredAt: string
}

export async function listGraphBuildFailures(params: {
  batchId?: string
  docNo?: string
  stage?: string
  reasonCode?: string
  limit?: number
}) {
  const response = await request.get('/knowledge/admin/graph/failures', { params })
  return (response.data.data ?? []) as GraphBuildFailure[]
}

export interface GraphFailureRecord {
  id: number
  batchId: string
  docNo: string
  entityKey?: string | null
  stage: string
  reasonCode: string
  detail: string
  retryable: boolean
  occurredAt: string
}

export async function fetchGraphFailures(params?: { batchId?: string; docNo?: string; stage?: string; reasonCode?: string; limit?: number }) {
  const response = await request.get('/knowledge/admin/graph/failures', { params })
  return (response.data.data ?? []) as GraphFailureRecord[]
}
