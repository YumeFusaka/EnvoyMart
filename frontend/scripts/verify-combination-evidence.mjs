/**
 * 组合注意事项专项只读验收。
 * 使用线上知识库、线上 Neo4j 和线上网关，不写入图谱，不拼接临时关系。
 * 运行：node scripts/verify-combination-evidence.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { COMBINATION_QUERY, loadCombinationEvidence } from './lib/combination-evidence.mjs'

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../.evidence/combination-interaction.json')
const query = COMBINATION_QUERY
const startedAt = new Date().toISOString()
const { report, combinations } = await loadCombinationEvidence(GW)

mkdirSync(resolve(OUT, '..'), { recursive: true })
writeFileSync(OUT, JSON.stringify({
  caseId: 'D2-COMBO-CALCIUM-IRON-ZINC', query, startedAt,
  finishedAt: new Date().toISOString(), endpoint: '/knowledge/graph/interactions',
  graph: report, evidence: combinations.map((edge) => ({ head: edge.head, tail: edge.tail, docId: edge.docId, docTitle: edge.docTitle, chunkId: edge.chunkId, quote: edge.quote, relation: edge.relation, effect: edge.effect, quoteStart: edge.quoteStart, quoteEnd: edge.quoteEnd })),
  chain: ['生产图谱查询', '三个实体与组合成员完整覆盖', '同一文档与切片身份核对', '引文偏移逐字核对', '成员尾端后果同一引文支撑'],
}, null, 2))
console.log(`组合注意事项专项通过：${combinations.length} 条真实组合关系，证据已写入 ${OUT}`)
