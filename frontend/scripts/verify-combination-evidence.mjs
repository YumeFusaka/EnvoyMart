/**
 * 组合禁忌专项只读验收。
 * 使用线上知识库、线上 Neo4j 和线上网关，不写入图谱，不拼接临时关系。
 * 运行：node scripts/verify-combination-evidence.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../.evidence/combination-interaction.json')
const query = '钙,铁剂,锌'
const startedAt = new Date().toISOString()
const response = await fetch(`${GW}/knowledge/graph/interactions?items=${encodeURIComponent(query)}`)
if (!response.ok) throw new Error(`图谱接口 HTTP ${response.status}`)
const body = await response.json()
if (body.code !== 200 || !body.data) throw new Error(`图谱接口失败：${body.msg ?? 'unknown'}`)
const report = body.data
if (!report.available) throw new Error(`图谱不可用：${report.note ?? 'unknown'}`)
if (!Array.isArray(report.items) || report.items.length !== 3) throw new Error('三项实体没有逐项返回')
if (report.items.some((item) => !item.found)) throw new Error('生产图谱未覆盖专项用例中的全部实体')
const risks = report.items.flatMap((item) => item.risks ?? [])
const combinations = risks.filter((edge) => edge.relation === 'COMBINED_WITH')
if (!combinations.length) throw new Error('生产图谱未返回组合禁忌关系；当前生产语料仅证明两两相互作用，不能冒充三成员组合证据')
if (combinations.some((edge) => !edge.quote || !edge.docId || !edge.docTitle)) throw new Error('组合禁忌边缺少原文引文或来源文档')

mkdirSync(resolve(OUT, '..'), { recursive: true })
writeFileSync(OUT, JSON.stringify({
  caseId: 'D2-COMBO-CALCIUM-IRON-VITAMIN-D', query, startedAt,
  finishedAt: new Date().toISOString(), endpoint: '/knowledge/graph/interactions',
  graph: report, evidence: combinations.map((edge) => ({ docId: edge.docId, docTitle: edge.docTitle, quote: edge.quote, relation: edge.relation, effect: edge.effect, quoteStart: edge.quoteStart, quoteEnd: edge.quoteEnd })),
  chain: ['production Neo4j interaction query', 'entity resolution', 'all-member combination traversal', 'quote-backed graph evidence'],
}, null, 2))
console.log(`组合禁忌专项通过：${combinations.length} 条真实组合关系，证据已写入 ${OUT}`)
