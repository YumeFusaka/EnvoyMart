/** 评测版本来自本地源码及公开只读接口；缺信息明确不可用，不填猜测的版本标签。 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, readdir } from 'node:fs/promises'
import { resolve, relative, basename } from 'node:path'

export const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const digestFiles = async (paths, root) => {
  const files = []
  for (const path of [...paths].sort()) files.push({ path: relative(root, path).replaceAll('\\', '/'), sha256: sha256(await readFile(path)) })
  return { sha256: sha256(JSON.stringify(files)), fileCount: files.length, files }
}
async function javaFiles(path) {
  const result = []
  for (const entry of await readdir(path, { withFileTypes: true })) {
    const full = resolve(path, entry.name)
    if (entry.isDirectory()) result.push(...await javaFiles(full))
    else if (entry.isFile() && entry.name.endsWith('.java')) result.push(full)
  }
  return result
}
export async function captureSourceMetadata(root) {
  const metadata = { capturedAt: new Date().toISOString(), available: false }
  try {
    const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', timeout: 10000 }).trim()
    const status = git(['status', '--porcelain=v1', '--untracked-files=normal'])
    metadata.git = { commit: git(['rev-parse', 'HEAD']), branch: git(['branch', '--show-current']), dirty: Boolean(status), status, statusSha256: sha256(status) }
    const paths = []
    for (const module of ['agent-core', 'ai-service', 'knowledge-service']) paths.push(...await javaFiles(resolve(root, 'backend', module, 'src/main/java')))
    const promptPaths = paths.filter((path) => basename(path).endsWith('Prompt.java') || path.replaceAll('\\', '/').includes('/agent/llm/'))
    metadata.promptVersion = await digestFiles(promptPaths, root)
    metadata.pipelineVersion = await digestFiles(paths, root)
    const runnerPaths = ['frontend/scripts/run-complex-agent-eval.mjs', 'frontend/scripts/fixtures/complex-agent-dialogues.json', 'frontend/scripts/lib/complex-agent-executor.mjs', 'frontend/scripts/lib/complex-agent-controls.mjs', 'frontend/scripts/lib/complex-agent-metadata.mjs'].map((path) => resolve(root, path))
    metadata.runnerVersion = await digestFiles(runnerPaths, root)
    metadata.sourceHash = sha256(JSON.stringify({ pipeline: metadata.pipelineVersion.sha256, runner: metadata.runnerVersion.sha256 }))
    metadata.sourceHashScope = 'backend agent-core/ai-service/knowledge-service src/main/java 与指定runner/fixture/control源码；包含当前落盘改动，不读取.env或运行产物'
    metadata.available = true
  } catch (error) { metadata.unavailable = error.message }
  return metadata
}

/** 兼容当前List接口及分页records接口，total表示全集时必须取完，禁止把首页当全集。 */
export async function captureCorpusMetadata(api) {
  const metadata = { capturedAt: new Date().toISOString(), available: false, documents: [], requests: [] }
  try {
    const summaries = []
    let page = 0; let total
    do {
      const response = await api(`/knowledge/documents?page=${page}&size=100`, { actor: 'anonymous' })
      metadata.requests.push({ path: `/knowledge/documents?page=${page}&size=100`, requestId: response.requestId })
      const payload = response.data
      if (Array.isArray(payload)) { summaries.push(...payload); total = summaries.length; break }
      if (!Array.isArray(payload?.records) || !Number.isInteger(payload.total) || payload.total < 0) throw new Error('文档列表既非数组也非有效records分页')
      if (total !== undefined && total !== payload.total) throw new Error('获取语料期间total发生变化，快照不完整')
      total = payload.total
      if (!payload.records.length && summaries.length < total) throw new Error('文档分页提前返回空页')
      summaries.push(...payload.records)
      page += 1
      if (page > 100) throw new Error('文档分页超过100页，拒绝不受限遍历')
    } while (summaries.length < total)
    if (summaries.length !== total || new Set(summaries.map((s) => s.docNo)).size !== total) throw new Error('语料数量或docNo唯一性不符合分页契约')
    for (const summary of summaries) {
      if (!summary.docNo || typeof summary.version !== 'string') throw new Error('文档列表缺docNo/version')
      const response = await api(`/knowledge/documents/${encodeURIComponent(summary.docNo)}`, { actor: 'anonymous' })
      metadata.requests.push({ path: `/knowledge/documents/${encodeURIComponent(summary.docNo)}`, requestId: response.requestId })
      const detail = response.data
      if (detail?.docNo !== summary.docNo || detail.version !== summary.version || typeof detail.content !== 'string') throw new Error(`文档${summary.docNo}详情/版本与列表不一致或缺正文`)
      metadata.documents.push({ docNo: detail.docNo, version: detail.version, status: detail.status, contentSha256: sha256(detail.content), contentLength: detail.content.length, chunkCount: Array.isArray(detail.chunks) ? detail.chunks.length : summary.chunkCount ?? null })
    }
    metadata.documents.sort((a, b) => a.docNo.localeCompare(b.docNo))
    metadata.count = metadata.documents.length
    metadata.activeCount = metadata.documents.filter((d) => d.status === 1).length
    metadata.contentSetSha256 = sha256(JSON.stringify(metadata.documents))
    metadata.available = true
  } catch (error) { metadata.unavailable = error.message; metadata.requestId = error.requestId }
  return metadata
}

export async function captureIndexMetadata(api) {
  try {
    const response = await api('/ai/admin/knowledge/status', { actor: 'admin' })
    const result = response.data?.result
    const counts = Number.isInteger(result?.documentCount) && Number.isInteger(result?.chunkCount)
      ? { available: true, documentCount: result.documentCount, chunkCount: result.chunkCount }
      : { available: false, unavailable: '索引状态尚无成功结果中的documentCount/chunkCount' }
    return { available: true, capturedAt: new Date().toISOString(), requestId: response.requestId, counts, status: response.data }
  } catch (error) { return { available: false, capturedAt: new Date().toISOString(), unavailable: error.message, requestId: error.requestId } }
}

export function aggregateModelUsage(cases) {
  const models = new Map()
  let promptTokens = 0; let completionTokens = 0; let totalTokens = 0; let noModelTurns = 0; let unavailableTurns = 0
  const turns = cases.flatMap((c) => [...(c.turns ?? []), ...(c.apiEvidence ?? []).filter((e) => e.tokenReplayed && e.data).map((e) => ({ usage: e.data.usage, usageEvidence: e.usageEvidence }))])
  for (const turn of turns) {
    if (turn.usageEvidence?.mode === 'NO_MODEL_CALL') noModelTurns += 1
    if (!turn.usage || !Array.isArray(turn.usage.models)) { unavailableTurns += 1; continue }
    promptTokens += turn.usage.promptTokens ?? 0; completionTokens += turn.usage.completionTokens ?? 0; totalTokens += turn.usage.totalTokens ?? 0
    for (const item of turn.usage.models) {
      if (!item.model) continue
      const value = models.get(item.model) ?? { model: item.model, promptTokens: 0, completionTokens: 0, reportedTurns: 0 }
      value.promptTokens += item.promptTokens ?? 0; value.completionTokens += item.completionTokens ?? 0; value.reportedTurns += 1
      models.set(item.model, value)
    }
  }
  return { models: [...models.values()].sort((a, b) => a.model.localeCompare(b.model)), promptTokens, completionTokens, totalTokens, noModelTurns, unavailableTurns, source: '本轮真实响应usage.models汇总；未返回数据不推测模型或用量' }
}
